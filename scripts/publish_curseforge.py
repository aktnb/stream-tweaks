#!/usr/bin/env python3
"""Optional CurseForge target publication using Upload API + Core API verification.

The two APIs have different credentials. No POST retry is authorized by a 404:
an append-only GitHub attempt is durable before upload, including across runs.
"""
from __future__ import annotations

import copy
import argparse
import hashlib
import json
import os
import pathlib
import re
import secrets
import urllib.error
import urllib.request

try:
    from . import publish_github as pg
except ImportError:
    import publish_github as pg


class PublishError(ValueError):
    """An unverified optional destination must not be treated as successful."""


class Mismatch(PublishError):
    """Existing remote content or metadata conflicts with the fixed target."""


class ApiError(PublishError):
    def __init__(self, status=None):
        self.status = status
        super().__init__('CurseForge request failed; do not automatically repost')


def require(condition, message):
    if not condition:
        raise PublishError(message)


def integer(value):
    return type(value) is int and value > 0


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        raise ApiError(None)


class CurseForgeClient:
    def __init__(self, token, api_key):
        require(isinstance(token, str) and bool(token), 'CURSEFORGE_TOKEN is required')
        require(isinstance(api_key, str) and bool(api_key), 'CURSEFORGE_API_KEY is required for hash verification')
        self.token, self.api_key = token, api_key

    def request(self, api, method, path, data=None, *, filename=None, content=None):
        require(api in {'upload', 'core'} and method in {'GET', 'POST'} and isinstance(path, str)
                and path.startswith('/') and not path.startswith('//') and '\r' not in path and '\n' not in path,
                'unexpected CurseForge request')
        require(api == 'upload' or method == 'GET', 'Core API is read-only')
        host = 'https://minecraft.curseforge.com' if api == 'upload' else 'https://api.curseforge.com'
        headers = {'Accept': 'application/json', 'User-Agent': 'aktnb/stream-tweaks release adapter',
                   ('X-Api-Token' if api == 'upload' else 'x-api-key'):
                       (self.token if api == 'upload' else self.api_key)}
        body = None
        if method == 'POST':
            require(re.fullmatch(r'/api/projects/[1-9][0-9]*/upload-file', path) is not None
                    and isinstance(data, dict) and isinstance(content, bytes)
                    and isinstance(filename, str) and re.fullmatch(r'[A-Za-z0-9.+-]+\.jar', filename),
                    'unexpected upload endpoint/content')
            boundary = 'streamtweaks' + secrets.token_hex(24)
            headers['Content-Type'] = 'multipart/form-data; boundary=' + boundary
            body = (f'--{boundary}\r\nContent-Disposition: form-data; name="metadata"\r\n'
                    'Content-Type: application/json\r\n\r\n').encode() + json.dumps(data).encode()
            body += (f'\r\n--{boundary}\r\nContent-Disposition: form-data; name="file"; '
                     f'filename="{filename}"\r\nContent-Type: application/java-archive\r\n\r\n').encode()
            body += content + f'\r\n--{boundary}--\r\n'.encode()
        request = urllib.request.Request(host + path, data=body, headers=headers, method=method)
        try:
            with urllib.request.build_opener(NoRedirect()).open(request, timeout=60) as response:
                return json.loads(response.read())
        except urllib.error.HTTPError as exc:
            raise ApiError(exc.code) from None
        except (urllib.error.URLError, TimeoutError, OSError, ValueError):
            raise ApiError(None) from None


def client_from_environment():
    return CurseForgeClient(os.environ.get('CURSEFORGE_TOKEN', ''), os.environ.get('CURSEFORGE_API_KEY', ''))


def resolve_target_metadata(client, minecraft, loader):
    """Resolve official Upload API IDs; never guess IDs or substitute another MC/loader."""
    require(loader in {'fabric', 'neoforge'}, 'unsupported loader')
    rows = client.request('upload', 'GET', '/api/game/versions')
    require(isinstance(rows, list), 'invalid game version catalog')
    names = [minecraft, 'Fabric' if loader == 'fabric' else 'NeoForge', 'Client']
    found = []
    for name in names:
        matches = [row for row in rows if isinstance(row, dict) and row.get('name') == name]
        require(len(matches) == 1 and integer(matches[0].get('id'))
                and integer(matches[0].get('gameVersionTypeID')), 'unknown or ambiguous game/loader ID')
        found.append(matches[0])
    require(len({row['id'] for row in found}) == 3
            and found[0]['gameVersionTypeID'] != found[1]['gameVersionTypeID'], 'conflicting game/loader IDs')
    return {'gameVersions': sorted(row['id'] for row in found), 'names': names}


def upload_metadata(directory, plan, target, resolved):
    return {'changelog': (pathlib.Path(directory) / f"notes-{target['loader']}.md").read_text(encoding='utf-8'),
            'changelogType': 'markdown', 'displayName': f"Stream Tweaks {plan['tag']} ({target['loader']})",
            'gameVersions': resolved['gameVersions'], 'releaseType': plan['channel'],
            'isMarkedForManualRelease': False}


def project_files(client, project):
    rows, seen, index, total = [], set(), 0, None
    while True:
        require(index < 10000, 'file listing exceeds API window; cannot prove absence')
        page = client.request('core', 'GET', f'/v1/mods/{project}/files?index={index}&pageSize=50')
        require(isinstance(page, dict) and isinstance(page.get('data'), list)
                and isinstance(page.get('pagination'), dict), 'invalid file listing')
        items, pagination = page['data'], page['pagination']
        require(type(pagination.get('index')) is int and pagination['index'] == index
                and type(pagination.get('resultCount')) is int and pagination['resultCount'] == len(items)
                and len(items) <= 50 and type(pagination.get('totalCount')) is int
                and 0 <= pagination['totalCount'] <= 10000, 'invalid file pagination')
        require(total is None or total == pagination['totalCount'], 'listing changed while reading; retry GET')
        total = pagination['totalCount']
        for item in items:
            require(isinstance(item, dict) and integer(item.get('id')) and item['id'] not in seen,
                    'duplicate/invalid file ID')
            seen.add(item['id'])
            rows.append(item)
        index += len(items)
        require(index <= total, 'inconsistent file count')
        if index == total:
            return rows
        require(bool(items), 'incomplete file listing')


def sha1_match(file, digest):
    hashes = file.get('hashes')
    return isinstance(hashes, list) and any(isinstance(h, dict) and type(h.get('algo')) is int
                                           and h['algo'] == 1 and h.get('value') == digest for h in hashes)


def inspect_file(client, project, metadata, resolved, artifact, digest, known_id=None):
    """Return (state, file ID). SHA1 is retrieved for the exact Core API file ID."""
    matches = [f for f in project_files(client, project)
               if f.get('fileName') == artifact['filename'] or f.get('displayName') == metadata['displayName']
               or sha1_match(f, digest)]
    require(len(matches) <= 1, 'ambiguous target files')
    if known_id is not None:
        require(re.fullmatch('[1-9][0-9]*', known_id) is not None, 'invalid saved file ID')
        require(not matches or matches[0]['id'] == int(known_id), 'saved file ID conflicts with listing')
    elif matches:
        known_id = str(matches[0]['id'])
    else:
        return 'absent', None
    try:
        response = client.request('core', 'GET', f'/v1/mods/{project}/files/{known_id}')
    except ApiError as exc:
        if exc.status == 404:
            return 'unknown', known_id
        raise
    require(isinstance(response, dict) and isinstance(response.get('data'), dict), 'invalid file response')
    file = response['data']
    if not (file.get('id') == int(known_id) and type(file.get('id')) is int
            and file.get('modId') == int(project) and type(file.get('modId')) is int
            and type(file.get('gameId')) is int and file['gameId'] == 432
            and file.get('fileName') == artifact['filename'] and file.get('displayName') == metadata['displayName']
            and type(file.get('fileLength')) is int and file['fileLength'] == artifact['size']
            and type(file.get('releaseType')) is int
            and file['releaseType'] == {'release': 1, 'beta': 2, 'alpha': 3}[metadata['releaseType']]
            and isinstance(file.get('gameVersions'), list) and len(file['gameVersions']) == 3
            and set(file['gameVersions']) == set(resolved['names'])):
        raise Mismatch('existing CurseForge file metadata differs')
    status = file.get('fileStatus')
    require(type(status) is int and status in range(1, 24), 'unknown file status')
    if status in {4, 10} and file.get('isAvailable') is True:
        if not sha1_match(file, digest):
            raise Mismatch('existing CurseForge file hash differs')
        return 'verified', known_id
    if status in {1, 3, 11, 18, 19, 20}:
        return 'pending_review', known_id
    return 'not_released', known_id


def reconcile_curseforge(client, github_client, directory, manifest, digest, state, *, state_output=None):
    """Return state for optional destinations, including failures, without finalizing."""
    directory = pathlib.Path(directory)
    configured = [t for t in manifest['plan']['targets'] if any(d['name'] == 'curseforge' for d in t['destinations'])]
    if not configured:
        for target in manifest['plan']['targets']:
            state['targets'].setdefault(target['id'], {})['curseforge'] = {'status': 'skipped', 'external_id': None}
        if state_output is not None:
            pg.write_state(state_output, directory, state)
        return copy.deepcopy(state)

    def save():
        if state_output is not None:
            pg.write_state(state_output, directory, state)

    for target in configured:
        artifact = next(a for a in manifest['artifacts'] if a['target_id'] == target['id'])
        receipt = {'status': 'unknown', 'external_id': None, 'sha256': artifact['sha256'], 'sha512': artifact['sha512']}
        state['targets'].setdefault(target['id'], {})['curseforge'] = receipt
        save()
        try:
            if client is None:
                client = client_from_environment()
            project = next(d['project'] for d in target['destinations'] if d['name'] == 'curseforge')
            remote_project = client.request('core', 'GET', '/v1/mods/' + project)
            require(isinstance(remote_project, dict) and isinstance(remote_project.get('data'), dict)
                    and type(remote_project['data'].get('id')) is int and remote_project['data']['id'] == int(project)
                    and type(remote_project['data'].get('gameId')) is int
                    and remote_project['data']['gameId'] == 432, 'project identity/game mismatch')
            resolved = resolve_target_metadata(client, manifest['plan']['minecraft'], target['loader'])
            metadata = upload_metadata(directory, manifest['plan'], target, resolved)
            journal = pg.read_publication_journal(github_client, state['github']['release_id'], manifest, digest)
            saved = [journal[(target['id'], 'curseforge', kind)]['external_id']
                     for kind in ['submitted', 'pending_review', 'verified']
                     if (target['id'], 'curseforge', kind) in journal]
            require(len(set(saved)) <= 1, 'conflicting saved file IDs')
            sha1 = hashlib.sha1((directory / artifact['filename']).read_bytes()).hexdigest()
            status, external_id = inspect_file(client, project, metadata, resolved, artifact, sha1,
                                               saved[0] if saved else None)
            receipt.update(status=status, external_id=external_id)
            if status == 'absent':
                receipt['status'] = 'unknown'
                save()
                require((target['id'], 'curseforge', 'attempt') not in journal and not saved,
                        'previous attempt outcome unknown; do not repost')
                destination = next(d for d in target['destinations'] if d['name'] == 'curseforge')
                require(state['github']['status'] == 'staged' or destination['required'] is False,
                        'cannot start required upload after GitHub finalization')
                record = pg.publication_record(manifest, digest, target['id'], 'curseforge', 'attempt')
                pg.append_publication_record(github_client, state['github']['release_id'], manifest, digest, record)
                receipt['status'] = 'unknown'
                save()
                try:
                    response = client.request('upload', 'POST', f'/api/projects/{project}/upload-file', metadata,
                                              filename=artifact['filename'],
                                              content=(directory / artifact['filename']).read_bytes())
                    require(isinstance(response, dict) and integer(response.get('id')), 'invalid upload file ID')
                    external_id = str(response['id'])
                    receipt['external_id'] = external_id
                    record = pg.publication_record(manifest, digest, target['id'], 'curseforge', 'submitted',
                                                   external_id=external_id)
                    pg.append_publication_record(github_client, state['github']['release_id'], manifest, digest, record)
                except ApiError:
                    external_id = None
                status, external_id = inspect_file(client, project, metadata, resolved, artifact, sha1, external_id)
                receipt.update(status='unknown' if status == 'absent' else status, external_id=external_id)
            if receipt['status'] in {'verified', 'pending_review'}:
                record = pg.publication_record(manifest, digest, target['id'], 'curseforge', receipt['status'],
                                               external_id=receipt['external_id'])
                pg.append_publication_record(github_client, state['github']['release_id'], manifest, digest, record)
        except Mismatch:
            receipt['status'] = 'mismatch'
        except (PublishError, pg.PublishError, ValueError, OSError, KeyError, TypeError):
            # The durable attempt remains authoritative even if local state is lost.
            receipt['status'] = 'unknown'
        save()
    return copy.deepcopy(state)


def preflight(client, directory, *, expected_source_sha, expected_manifest_sha256):
    """GET-only capability check. It does not prove the author's upload permission."""
    manifest = pg.rb.verify_bundle(directory, expected_source_sha=expected_source_sha,
                                    expected_manifest_sha256=expected_manifest_sha256)
    results = []
    for target in manifest['plan']['targets']:
        destinations = [d for d in target['destinations'] if d['name'] == 'curseforge']
        if not destinations:
            results.append({'target': target['id'], 'status': 'skipped'})
            continue
        if client is None:
            client = client_from_environment()
        project = destinations[0]['project']
        response = client.request('core', 'GET', '/v1/mods/' + project)
        require(isinstance(response, dict) and isinstance(response.get('data'), dict)
                and type(response['data'].get('id')) is int and response['data']['id'] == int(project)
                and type(response['data'].get('gameId')) is int and response['data']['gameId'] == 432,
                'project identity/game mismatch')
        resolved = resolve_target_metadata(client, manifest['plan']['minecraft'], target['loader'])
        artifact = next(a for a in manifest['artifacts'] if a['target_id'] == target['id'])
        status, external_id = inspect_file(client, project, upload_metadata(directory, manifest['plan'], target, resolved),
                                           resolved, artifact,
                                           hashlib.sha1((pathlib.Path(directory) / artifact['filename']).read_bytes()).hexdigest())
        results.append({'target': target['id'], 'status': status, 'external_id': external_id,
                        'game_version_ids': resolved['gameVersions']})
    return {'targets': results}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['preflight'])
    parser.add_argument('--bundle', required=True, type=pathlib.Path)
    parser.add_argument('--source-sha', required=True)
    parser.add_argument('--manifest-sha256', required=True)
    args = parser.parse_args(argv)
    try:
        result = preflight(None, args.bundle, expected_source_sha=args.source_sha,
                           expected_manifest_sha256=args.manifest_sha256)
        print(pg.rb.canonical_json(result), end='')
    except (PublishError, pg.PublishError, pg.rb.BundleError, ValueError, OSError, KeyError, TypeError):
        print('CurseForge preflight stopped: credentials, project, metadata, or file identity not verified.',
              file=__import__('sys').stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
