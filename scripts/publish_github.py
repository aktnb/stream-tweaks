#!/usr/bin/env python3
"""Stage/resume a verified bundle in GitHub without deleting or replacing remote objects.

The operational token is read only from GITHUB_TOKEN. No API response bodies or
request headers are printed on failure. Finalize is a hook for the publish coordinator.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import pathlib
import re
import tempfile
import urllib.error
import urllib.parse
import urllib.request

try:
    from . import release_bundle as rb, release_plan as rp, validate_targets as vt
except ImportError:
    import release_bundle as rb
    import release_plan as rp
    import validate_targets as vt

REPOSITORY = 'aktnb/stream-tweaks'
BASE = '/repos/' + REPOSITORY


class PublishError(ValueError):
    """Mismatch or unprovable publication state; stop rather than overwrite/repost."""


class ApiError(PublishError):
    def __init__(self, status: int | None):
        self.status = status
        super().__init__(f'GitHub request failed (HTTP {status or "unknown"}); re-query before retry')


def require(condition: bool, message: str) -> None:
    if not condition:
        raise PublishError(message)


class DownloadRedirect(urllib.request.HTTPRedirectHandler):
    """Signed asset redirects must not forward the GitHub authorization header."""
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        parsed = urllib.parse.urlsplit(newurl)
        require(parsed.scheme == 'https' and parsed.hostname in {
            'release-assets.githubusercontent.com', 'objects.githubusercontent.com'}, 'untrusted asset redirect')
        return urllib.request.Request(newurl, headers={'Accept': 'application/octet-stream'})


class GitHubClient:
    def __init__(self, token: str):
        require(bool(token), 'GITHUB_TOKEN is required')
        self.token = token

    def request(self, method: str, path: str, data=None, *, binary: bool = False):
        require(path.startswith(BASE + '/') and not path.startswith('//'), 'unexpected API path')
        host = 'https://uploads.github.com' if method == 'POST' and '/assets?' in path else 'https://api.github.com'
        body = data if isinstance(data, bytes) else json.dumps(data).encode() if data is not None else None
        headers = {'Authorization': 'Bearer ' + self.token, 'X-GitHub-Api-Version': '2022-11-28',
                   'Accept': 'application/octet-stream' if binary else 'application/vnd.github+json',
                   'Content-Type': 'application/octet-stream' if isinstance(data, bytes) else 'application/json',
                   'User-Agent': 'stream-tweaks-release'}
        request = urllib.request.Request(host + path, data=body, headers=headers, method=method)
        try:
            with urllib.request.build_opener(DownloadRedirect()).open(request, timeout=60) as response:
                raw = response.read()
                return raw if binary else json.loads(raw)
        except urllib.error.HTTPError as exc:
            raise ApiError(exc.code) from None
        except (urllib.error.URLError, TimeoutError, OSError, ValueError):
            raise ApiError(None) from None


def optional_get(client, path):
    try:
        return client.request('GET', path)
    except ApiError as exc:
        if exc.status == 404:
            return None
        raise


def list_pages(client, path):
    result = []
    for page in range(1, 10001):
        rows = client.request('GET', path + f'?per_page=100&page={page}')
        require(isinstance(rows, list), 'invalid GitHub list response')
        result.extend(rows)
        if len(rows) < 100:
            return result
    raise PublishError('GitHub list pagination limit reached')


def read_tag(client, tag: str):
    ref = optional_get(client, BASE + '/git/ref/tags/' + urllib.parse.quote(tag, safe=''))
    if ref is None:
        return None
    require(ref.get('ref') == 'refs/tags/' + tag, 'tag ref mismatch')
    obj = ref.get('object', {})
    require(obj.get('type') == 'tag', 'existing tag is not annotated')
    seen = set()
    for _ in range(10):
        sha = obj.get('sha')
        require(isinstance(sha, str) and re.fullmatch('[0-9a-f]{40}', sha) and sha not in seen,
                'invalid/cyclic annotated tag')
        seen.add(sha)
        annotation = client.request('GET', BASE + '/git/tags/' + sha)
        if len(seen) == 1:
            require(annotation.get('tag') == tag, 'annotation tag mismatch')
        obj = annotation.get('object', {})
        if obj.get('type') == 'commit':
            require(re.fullmatch('[0-9a-f]{40}', obj.get('sha', '')) is not None, 'invalid peeled commit')
            return obj['sha']
        require(obj.get('type') == 'tag', 'tag does not point to a commit')
    raise PublishError('annotated tag nesting limit reached')


def ensure_tag(client, plan):
    actual = read_tag(client, plan['tag'])
    if actual is not None:
        require(actual == plan['source_sha'], 'existing tag points to different source SHA')
        return
    # An unattached tag object may be left if response is lost; it is not a published tag.
    try:
        annotation = client.request('POST', BASE + '/git/tags', {
            'tag': plan['tag'], 'message': 'Release ' + plan['tag'] + '\nLoaders: ' +
                ','.join(t['loader'] for t in plan['targets']) + '\n',
            'object': plan['source_sha'], 'type': 'commit'})
        require(re.fullmatch('[0-9a-f]{40}', annotation.get('sha', '')) is not None, 'invalid new tag SHA')
        client.request('POST', BASE + '/git/refs', {'ref': 'refs/tags/' + plan['tag'], 'sha': annotation['sha']})
    except ApiError:
        require(read_tag(client, plan['tag']) == plan['source_sha'], 'tag creation outcome is unknown; stop')
        return
    require(read_tag(client, plan['tag']) == plan['source_sha'], 'new tag source SHA mismatch')


def find_release(client, tag):
    # Listing includes drafts for authenticated contents-write callers; by-tag may omit drafts.
    matches = [r for r in list_pages(client, BASE + '/releases') if r.get('tag_name') == tag]
    require(len(matches) <= 1, 'multiple releases for fixed tag')
    return matches[0] if matches else None


def validate_release(release, plan):
    require(isinstance(release, dict) and type(release.get('id')) is int and release['id'] > 0,
            'invalid release ID')
    require(release.get('tag_name') == plan['tag'], 'release tag mismatch')
    require(release.get('prerelease') is (plan['channel'] != 'release'), 'release channel mismatch')
    require(type(release.get('draft')) is bool, 'invalid draft status')


def assets(client, release_id):
    rows = list_pages(client, BASE + f'/releases/{release_id}/assets')
    result = {}
    for asset in rows:
        require(isinstance(asset, dict) and isinstance(asset.get('name'), str), 'invalid asset')
        require(asset['name'] not in result, 'duplicate asset names; stop')
        require(type(asset.get('id')) is int and asset['id'] > 0, 'invalid asset ID')
        result[asset['name']] = asset
    return result


def download_asset(client, asset):
    require(asset.get('state') == 'uploaded', 'asset upload incomplete; do not replace')
    raw = client.request('GET', BASE + f"/releases/assets/{asset['id']}", binary=True)
    require(isinstance(raw, bytes) and asset.get('size') == len(raw), 'asset size mismatch')
    digest = asset.get('digest')
    if digest is not None:
        require(digest == 'sha256:' + hashlib.sha256(raw).hexdigest(), 'GitHub asset digest mismatch')
    return raw


def verify_asset(client, asset, raw):
    require(download_asset(client, asset) == raw, 'existing asset hash/content mismatch: ' + asset['name'])


def publication_record(manifest, digest, target_id, destination, status, *, external_id=None):
    """Canonical append-only journal. Attempt existence never proves remote success."""
    plan = rp.validate_plan(manifest['plan'])
    require(re.fullmatch('[0-9a-f]{64}', digest or '') is not None, 'invalid journal manifest hash')
    target = next((t for t in plan['targets'] if t['id'] == target_id), None)
    require(target is not None and destination in {'modrinth', 'curseforge'}
            and any(d['name'] == destination for d in target['destinations']), 'unexpected journal destination')
    allowed = {'attempt', 'verified'} | ({'submitted', 'pending_review'} if destination == 'curseforge' else set())
    require(status in allowed, 'unexpected journal status')
    require((status == 'attempt' and external_id is None) or (status != 'attempt'
            and isinstance(external_id, str) and re.fullmatch('[0-9A-Za-z]+', external_id)),
            'invalid journal external ID')
    require(destination != 'curseforge' or external_id is None or re.fullmatch('[1-9][0-9]*', external_id),
            'invalid CurseForge file ID')
    artifact = next(a for a in manifest['artifacts'] if a['target_id'] == target_id)
    return {'schema_version': 1, 'release_id': plan['release_id'], 'manifest_sha256': digest,
            'target_id': target_id, 'destination': destination, 'status': status,
            'external_id': external_id, 'sha256': artifact['sha256'], 'sha512': artifact['sha512']}


def publication_record_name(record):
    return f"publish-state-{record['target_id']}-{record['destination']}-{record['status']}.json"


def _partition_assets(client, remote, manifest, digest):
    """Only a validated journal namespace is separate from the immutable bundle."""
    bundle, records = {}, {}
    for name, asset in remote.items():
        if not name.startswith('publish-state-'):
            bundle[name] = asset
            continue
        require(type(asset.get('size')) is int and 0 < asset['size'] <= 4096, 'invalid journal asset size')
        raw = download_asset(client, asset)
        record = json.loads(raw, object_pairs_hook=vt._unique_object)
        require(isinstance(record, dict) and set(record) == {'schema_version', 'release_id', 'manifest_sha256',
                'target_id', 'destination', 'status', 'external_id', 'sha256', 'sha512'}, 'invalid journal fields')
        expected = publication_record(manifest, digest, record['target_id'], record['destination'],
                                      record['status'], external_id=record['external_id'])
        require(record == expected and type(record['schema_version']) is int
                and raw == rb.canonical_json(expected).encode() and name == publication_record_name(expected),
                'journal identity/content mismatch')
        key = (record['target_id'], record['destination'], record['status'])
        require(key not in records, 'duplicate publication journal record')
        records[key] = record
    return bundle, records


def read_publication_journal(client, release_id, manifest, digest):
    return _partition_assets(client, assets(client, release_id), manifest, digest)[1]


def append_publication_record(client, release_id, manifest, digest, record):
    """Write once and re-read. An uncertain journal write forbids an external POST."""
    expected = publication_record(manifest, digest, record['target_id'], record['destination'],
                                  record['status'], external_id=record['external_id'])
    require(record == expected, 'invalid requested journal record')
    name = publication_record_name(record)
    raw = rb.canonical_json(record).encode()
    remote = assets(client, release_id)
    _partition_assets(client, remote, manifest, digest)
    if name in remote:
        verify_asset(client, remote[name], raw)
        return
    release = find_release(client, manifest['plan']['tag'])
    validate_release(release, manifest['plan'])
    require(release['id'] == release_id, 'journal release mismatch')
    # A previously planned optional CurseForge destination may be filled later.
    # This never adds a destination/target or relaxes mandatory publication gates.
    target = next(t for t in manifest['plan']['targets'] if t['id'] == record['target_id'])
    optional_cf = record['destination'] == 'curseforge' and any(
        d['name'] == 'curseforge' and d['required'] is False for d in target['destinations'])
    require(release['draft'] or record['status'] == 'verified' or optional_cf,
            'cannot start required attempt after finalization')
    try:
        client.request('POST', BASE + f'/releases/{release_id}/assets?name=' + urllib.parse.quote(name, safe=''), raw)
    except ApiError:
        pass
    remote = assets(client, release_id)
    require(name in remote, 'publication journal outcome unknown; stop before external POST')
    verify_asset(client, remote[name], raw)
    _partition_assets(client, remote, manifest, digest)


def write_state(path, directory, state):
    path, directory = pathlib.Path(path), pathlib.Path(directory)
    require(not path.resolve().is_relative_to(directory.resolve()) and not path.is_symlink(),
            'state must be outside immutable bundle')
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(mode='w', encoding='utf-8', dir=path.parent, delete=False) as stream:
        stream.write(rb.canonical_json(state))
        temporary = pathlib.Path(stream.name)
    temporary.replace(path)


def stage_bundle(client, directory, *, expected_source_sha, expected_manifest_sha256, expected_plan=None):
    """Full validation precedes the first write. Only missing assets are added."""
    directory = pathlib.Path(directory)
    manifest = rb.verify_bundle(directory, expected_source_sha=expected_source_sha,
                                expected_manifest_sha256=expected_manifest_sha256, expected_plan=expected_plan)
    plan = manifest['plan']
    ensure_tag(client, plan)
    release = find_release(client, plan['tag'])
    if release is None:
        try:
            client.request('POST', BASE + '/releases', {'tag_name': plan['tag'], 'target_commitish': plan['source_sha'],
                'name': 'Stream Tweaks ' + plan['tag'], 'body': (directory / 'notes-combined.md').read_text(),
                'draft': True, 'prerelease': plan['channel'] != 'release'})
        except ApiError:
            # Never POST twice: re-query to prove the original operation succeeded.
            pass
        release = find_release(client, plan['tag'])
        require(release is not None, 'release creation outcome unknown; stop')
    validate_release(release, plan)
    remote, _ = _partition_assets(client, assets(client, release['id']), manifest, expected_manifest_sha256)
    expected = {p.name: p.read_bytes() for p in directory.iterdir()}
    require(set(remote) <= set(expected), 'unexpected release assets; stop')
    # Inspect every existing asset before adding any missing one.
    for name, asset in remote.items():
        verify_asset(client, asset, expected[name])
    for name, raw in sorted(expected.items()):
        if name in remote:
            verify_asset(client, remote[name], raw)
            continue
        require(release['draft'], 'published release missing bundle assets; stop')
        try:
            client.request('POST', BASE + f"/releases/{release['id']}/assets?name=" + urllib.parse.quote(name, safe=''), raw)
        except ApiError:
            pass
        remote, _ = _partition_assets(client, assets(client, release['id']), manifest, expected_manifest_sha256)
        require(name in remote, 'asset upload outcome unknown; stop without repost: ' + name)
        verify_asset(client, remote[name], raw)
    # The durable-storage gate is a fresh GET of every binary, not upload return values.
    remote, _ = _partition_assets(client, assets(client, release['id']), manifest, expected_manifest_sha256)
    require(set(remote) == set(expected), 'stored bundle asset set mismatch')
    for name, raw in expected.items():
        verify_asset(client, remote[name], raw)
    return {'schema_version': 1, 'release_id': plan['release_id'], 'manifest_sha256': expected_manifest_sha256,
            'github': {'status': 'staged' if release['draft'] else 'published', 'release_id': release['id'],
                       'assets': {name: asset['id'] for name, asset in sorted(remote.items())}}}


def restore_bundle(client, tag, output, *, expected_source_sha, expected_manifest_sha256):
    """Recover the exact durable bundle. Missing data never triggers a rebuild/upload."""
    output = pathlib.Path(output)
    require(not output.exists(), 'restore output exists; verify it instead')
    require(read_tag(client, tag) == expected_source_sha, 'tag source SHA mismatch/missing')
    release = find_release(client, tag)
    require(release is not None, 'saved release bundle does not exist')
    remote = assets(client, release['id'])
    require(rb.MANIFEST in remote, 'stored manifest is missing; cannot rebuild blindly')
    raw_manifest = download_asset(client, remote[rb.MANIFEST])
    require(hashlib.sha256(raw_manifest).hexdigest() == expected_manifest_sha256, 'stored manifest hash mismatch')
    manifest = json.loads(raw_manifest, object_pairs_hook=vt._unique_object)
    plan = rp.validate_plan(manifest['plan'])
    require(plan['tag'] == tag and plan['source_sha'] == expected_source_sha, 'stored bundle identity mismatch')
    validate_release(release, plan)
    remote, _ = _partition_assets(client, remote, manifest, expected_manifest_sha256)
    expected = {rb.MANIFEST, rb.PLAN, rb.PROPERTIES, 'notes-combined.md'} | {
        row['filename'] for row in plan['targets']} | {f"notes-{row['loader']}.md" for row in plan['targets']}
    require(set(remote) == expected, 'saved bundle incomplete/extra assets; recover original artifact, do not rebuild')
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.github-bundle-', dir=output.parent) as temporary:
        stage = pathlib.Path(temporary) / 'bundle'
        stage.mkdir()
        for name in sorted(expected):
            (stage / name).write_bytes(download_asset(client, remote[name]))
        rb.verify_bundle(stage, expected_source_sha=expected_source_sha, expected_manifest_sha256=expected_manifest_sha256)
        stage.rename(output)
    return plan


def finalize_release(client, directory, *, expected_source_sha, expected_manifest_sha256, required_successes):
    """Coordinator hook. The caller must supply independently verified destination receipts."""
    manifest = rb.verify_bundle(directory, expected_source_sha=expected_source_sha,
                                expected_manifest_sha256=expected_manifest_sha256)
    required = {(t['id'], d['name']) for t in manifest['plan']['targets'] for d in t['destinations'] if d['required']}
    require(required <= set(required_successes), 'required publication destinations not verified')
    state = stage_bundle(client, directory, expected_source_sha=expected_source_sha,
                         expected_manifest_sha256=expected_manifest_sha256)
    release_id = state['github']['release_id']
    try:
        client.request('PATCH', BASE + f'/releases/{release_id}', {'draft': False})
    except ApiError:
        pass
    release = find_release(client, manifest['plan']['tag'])
    validate_release(release, manifest['plan'])
    require(release['draft'] is False, 'finalize outcome unknown; re-query before retry')
    state['github']['status'] = 'published'
    return state


def validate_build_run(client, run_id, source_sha):
    require(isinstance(run_id, str) and re.fullmatch('[1-9][0-9]*', run_id), 'invalid build run ID')
    require(re.fullmatch('[0-9a-f]{40}', source_sha), 'full source SHA required')
    run = client.request('GET', BASE + '/actions/runs/' + run_id)
    require(run.get('event') == 'workflow_dispatch' and run.get('conclusion') == 'success'
            and run.get('head_sha') == source_sha and run.get('path') == '.github/workflows/release.yml'
            and run.get('repository', {}).get('full_name') == REPOSITORY
            and run.get('head_repository', {}).get('full_name') == REPOSITORY, 'untrusted/failed bundle build run')
    vt.validate_source_branch(run.get('head_branch'))
    return run


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='mode', required=True)
    for mode in ['stage', 'restore', 'validate-run']:
        cmd = sub.add_parser(mode)
        cmd.add_argument('--source-sha', required=True)
        if mode != 'validate-run':
            cmd.add_argument('--manifest-sha256', required=True)
        if mode == 'stage':
            cmd.add_argument('--bundle', required=True, type=pathlib.Path)
            cmd.add_argument('--state-output', required=True, type=pathlib.Path)
            cmd.add_argument('--plan', type=pathlib.Path)
        elif mode == 'restore':
            cmd.add_argument('--tag', required=True)
            cmd.add_argument('--output', required=True, type=pathlib.Path)
        else:
            cmd.add_argument('--run-id', required=True)
    args = parser.parse_args(argv)
    try:
        client = GitHubClient(os.environ.get('GITHUB_TOKEN', ''))
        if args.mode == 'validate-run':
            result = validate_build_run(client, args.run_id, args.source_sha)
            print(rb.canonical_json({'run_id': result['id'], 'source_sha': result['head_sha']}), end='')
        else:
            require(re.fullmatch('[0-9a-f]{64}', args.manifest_sha256), 'manifest SHA256 required')
            if args.mode == 'stage':
                require(not args.state_output.resolve().is_relative_to(args.bundle.resolve()), 'state must be outside immutable bundle')
                result = stage_bundle(client, args.bundle, expected_source_sha=args.source_sha,
                                      expected_manifest_sha256=args.manifest_sha256,
                                      expected_plan=rp.load_plan(args.plan) if args.plan else None)
                write_state(args.state_output, args.bundle, result)
            else:
                rp.require(isinstance(args.tag, str) and re.fullmatch(r'v[0-9A-Za-z.+-]+', args.tag), 'invalid tag')
                result = restore_bundle(client, args.tag, args.output, expected_source_sha=args.source_sha,
                                        expected_manifest_sha256=args.manifest_sha256)
            print(rb.canonical_json(result), end='')
    except (PublishError, rb.BundleError, rp.PlanError, vt.ValidationError, OSError, ValueError, KeyError, TypeError):
        # Do not print arbitrary server/input strings: they can contain secrets or control characters.
        print('GitHub publication stopped: identity, hash, permissions, or remote state could not be verified.', file=__import__('sys').stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
