#!/usr/bin/env python3
"""Reconcile fixed Modrinth versions, then finalize only verified required destinations.

API: https://docs.modrinth.com/api/operations/createversion/
Hash lookup accepts SHA512 (not the manifest's internal SHA256).
Attempt records are persisted in the GitHub draft before any external POST.
"""
from __future__ import annotations

import argparse
import copy
import json
import os
import pathlib
import re
import secrets
import urllib.error
import urllib.parse
import urllib.request

try:
    from . import publish_github as pg, release_bundle as rb
except ImportError:
    import publish_github as pg
    import release_bundle as rb


class PublishError(ValueError):
    """A mismatch or uncertain remote result must not be overwritten/reposted."""


class ApiError(PublishError):
    def __init__(self, status=None):
        self.status = status
        super().__init__('Modrinth request failed; re-query before retry')


def require(condition, message):
    if not condition:
        raise PublishError(message)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        raise ApiError(None)


class ModrinthClient:
    def __init__(self, token):
        require(isinstance(token, str) and bool(token), 'MODRINTH_TOKEN is required')
        self.token = token

    def request(self, method, path, data=None, *, filename=None, content=None):
        require(method in {'GET', 'POST'} and isinstance(path, str) and path.startswith('/')
                and not path.startswith('//') and '\r' not in path and '\n' not in path,
                'unexpected Modrinth request')
        headers = {'Authorization': self.token, 'User-Agent': 'aktnb/stream-tweaks release adapter',
                   'Accept': 'application/json'}
        body = None
        if method == 'POST':
            require(path == '/version' and isinstance(data, dict), 'unexpected publication endpoint')
            require(isinstance(filename, str) and re.fullmatch(r'[A-Za-z0-9.+-]+\.jar', filename),
                    'invalid upload filename')
            require(isinstance(content, bytes), 'missing upload bytes')
            boundary = 'streamtweaks' + secrets.token_hex(24)
            headers['Content-Type'] = 'multipart/form-data; boundary=' + boundary
            body = (f'--{boundary}\r\nContent-Disposition: form-data; name="data"\r\n'
                    'Content-Type: application/json\r\n\r\n').encode() + json.dumps(data).encode()
            body += (f'\r\n--{boundary}\r\nContent-Disposition: form-data; name="jar"; '
                     f'filename="{filename}"\r\nContent-Type: application/java-archive\r\n\r\n').encode()
            body += content + f'\r\n--{boundary}--\r\n'.encode()
        request = urllib.request.Request('https://api.modrinth.com/v2' + path,
                                         data=body, headers=headers, method=method)
        try:
            with urllib.request.build_opener(NoRedirect()).open(request, timeout=60) as response:
                return json.loads(response.read())
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


def destination(target):
    return next(d for d in target['destinations'] if d['name'] == 'modrinth')


def version_metadata(directory, plan, target):
    """The metadata contract is derived only from immutable bundle inputs."""
    deps = ([{'project_id': 'P7dR8mSH', 'dependency_type': 'required'},
             {'project_id': 'mOgUt4GM', 'dependency_type': 'optional'}]
            if target['loader'] == 'fabric' else [])
    return {'project_id': destination(target)['project'], 'version_number': target['modrinth_version'],
            'name': f"Stream Tweaks {plan['tag']} ({target['loader'].capitalize() if target['loader'] == 'fabric' else 'NeoForge'})",
            'version_type': plan['channel'], 'game_versions': [plan['minecraft']], 'loaders': [target['loader']],
            'changelog': (pathlib.Path(directory) / f"notes-{target['loader']}.md").read_text(encoding='utf-8'),
            'environment': 'client_only', 'dependencies': deps, 'featured': False,
            'status': 'listed', 'file_parts': ['jar'], 'primary_file': 'jar'}


def project_versions(client, project):
    # This endpoint returns the whole list; it has no limit/offset pagination contract.
    rows = client.request('GET', '/project/' + urllib.parse.quote(project, safe='') + '/version')
    require(isinstance(rows, list), 'invalid project version listing')
    return rows


def validate_version(version, metadata, artifact):
    require(isinstance(version, dict) and isinstance(version.get('id'), str)
            and re.fullmatch('[0-9A-Za-z]+', version['id']), 'invalid external version ID')
    for key in ['project_id', 'version_number', 'name', 'version_type', 'game_versions', 'loaders',
                'changelog', 'environment', 'featured', 'status']:
        require(version.get(key) == metadata[key] and type(version.get(key)) is type(metadata[key]),
                'existing version metadata mismatch: ' + key)
    deps = version.get('dependencies')
    require(isinstance(deps, list), 'invalid dependency metadata')
    normalized = []
    for dep in deps:
        require(isinstance(dep, dict) and dep.get('version_id') is None and dep.get('file_name') is None,
                'dependency version/file mismatch')
        normalized.append({key: dep.get(key) for key in ['project_id', 'dependency_type']})
    require(sorted(normalized, key=lambda d: str(d['project_id']))
            == sorted(metadata['dependencies'], key=lambda d: d['project_id']), 'dependency contract mismatch')
    files = version.get('files')
    require(isinstance(files, list) and len(files) == 1, 'expected exactly one normal JAR')
    file = files[0]
    require(isinstance(file, dict) and file.get('filename') == artifact['filename']
            and type(file.get('size')) is int and file['size'] == artifact['size']
            and file.get('primary') is True and file.get('file_type') is None
            and isinstance(file.get('hashes'), dict) and file['hashes'].get('sha512') == artifact['sha512'],
            'normal JAR identity/hash mismatch')
    return version['id']


def inspect_version(client, directory, plan, target, artifact):
    metadata = version_metadata(directory, plan, target)
    matches = [v for v in project_versions(client, metadata['project_id'])
               if isinstance(v, dict) and v.get('version_number') == metadata['version_number']]
    require(len(matches) <= 1, 'duplicate external version numbers')
    hashed = optional_get(client, '/version_file/' + artifact['sha512'] + '?algorithm=sha512')
    if not matches:
        require(hashed is None, 'hash exists under a different project/version; stop')
        return None
    external_id = validate_version(matches[0], metadata, artifact)
    require(hashed is not None, 'version/hash lookup disagreement; stop')
    require(validate_version(hashed, metadata, artifact) == external_id, 'hash lookup version ID mismatch')
    return external_id


def reconcile_modrinth(client, github_client, directory, *, expected_source_sha, expected_manifest_sha256,
                       state_output=None):
    """Fresh remote verification is authoritative; saved receipts are never success proof."""
    directory = pathlib.Path(directory)
    manifest = rb.verify_bundle(directory, expected_source_sha=expected_source_sha,
                                expected_manifest_sha256=expected_manifest_sha256)
    plan = manifest['plan']
    state = pg.stage_bundle(github_client, directory, expected_source_sha=expected_source_sha,
                            expected_manifest_sha256=expected_manifest_sha256)
    release_id = state['github']['release_id']
    state['targets'] = {}
    journal = pg.read_publication_journal(github_client, release_id, manifest, expected_manifest_sha256)
    artifacts = {a['target_id']: a for a in manifest['artifacts']}
    observed = {t['id']: inspect_version(client, directory, plan, t, artifacts[t['id']]) for t in plan['targets']}

    def save():
        if state_output is not None:
            pg.write_state(state_output, directory, state)

    for target in plan['targets']:
        target_id = target['id']
        artifact = artifacts[target_id]
        state['targets'][target_id] = {'modrinth': {'status': 'unknown', 'external_id': None,
                                                   'sha256': artifact['sha256'], 'sha512': artifact['sha512']}}
        save()
        external_id = observed[target_id]
        if external_id is None:
            require((target_id, 'modrinth', 'attempt') not in journal
                    and (target_id, 'modrinth', 'verified') not in journal,
                    'previous attempt outcome unknown; do not POST again')
            require(state['github']['status'] == 'staged', 'published GitHub release is missing a destination')
            record = pg.publication_record(manifest, expected_manifest_sha256, target_id, 'modrinth', 'attempt')
            pg.append_publication_record(github_client, release_id, manifest, expected_manifest_sha256, record)
            try:
                client.request('POST', '/version', version_metadata(directory, plan, target),
                               filename=artifact['filename'], content=(directory / artifact['filename']).read_bytes())
            except ApiError:
                pass
            external_id = inspect_version(client, directory, plan, target, artifact)
            require(external_id is not None, 'publication outcome unknown; re-query without repost')
        record = pg.publication_record(manifest, expected_manifest_sha256, target_id, 'modrinth',
                                       'verified', external_id=external_id)
        pg.append_publication_record(github_client, release_id, manifest, expected_manifest_sha256, record)
        state['targets'][target_id]['modrinth'].update(status='verified', external_id=external_id)
        save()
    return copy.deepcopy(state)


def publish_and_finalize(client, github_client, directory, *, expected_source_sha,
                         expected_manifest_sha256, state_output=None, curseforge_client=None):
    state = reconcile_modrinth(client, github_client, directory, expected_source_sha=expected_source_sha,
                               expected_manifest_sha256=expected_manifest_sha256, state_output=state_output)
    manifest = rb.verify_bundle(directory, expected_source_sha=expected_source_sha,
                                expected_manifest_sha256=expected_manifest_sha256)
    try:
        from . import publish_curseforge as cf
    except ImportError:
        import publish_curseforge as cf
    state = cf.reconcile_curseforge(curseforge_client, github_client, directory, manifest,
                                    expected_manifest_sha256, state, state_output=state_output)
    successes = {(t['id'], 'github') for t in manifest['plan']['targets']}
    successes |= {(target_id, 'modrinth') for target_id, values in state['targets'].items()
                  if values['modrinth']['status'] == 'verified'}
    successes |= {(target_id, 'curseforge') for target_id, values in state['targets'].items()
                  if values.get('curseforge', {}).get('status') == 'verified'}
    required = {(t['id'], d['name']) for t in manifest['plan']['targets'] for d in t['destinations'] if d['required']}
    require(required <= successes, 'other required destinations are not independently verified')
    finalized = pg.finalize_release(github_client, directory, expected_source_sha=expected_source_sha,
                                    expected_manifest_sha256=expected_manifest_sha256, required_successes=successes)
    state['github'] = finalized['github']
    if state_output is not None:
        pg.write_state(state_output, pathlib.Path(directory), state)
    return state


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['reconcile', 'publish'])
    parser.add_argument('--bundle', required=True, type=pathlib.Path)
    parser.add_argument('--source-sha', required=True)
    parser.add_argument('--manifest-sha256', required=True)
    parser.add_argument('--state-output', required=True, type=pathlib.Path)
    args = parser.parse_args(argv)
    try:
        require(not args.state_output.resolve().is_relative_to(args.bundle.resolve()), 'state must be outside bundle')
        operation = publish_and_finalize if args.mode == 'publish' else reconcile_modrinth
        result = operation(ModrinthClient(os.environ.get('MODRINTH_TOKEN', '')),
                           pg.GitHubClient(os.environ.get('GITHUB_TOKEN', '')), args.bundle,
                           expected_source_sha=args.source_sha, expected_manifest_sha256=args.manifest_sha256,
                           state_output=args.state_output)
        print(rb.canonical_json(result), end='')
    except (PublishError, pg.PublishError, rb.BundleError, ValueError, OSError, KeyError, TypeError):
        print('Publication stopped: identity, hash, permissions, or remote outcome could not be verified.',
              file=__import__('sys').stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
