#!/usr/bin/env python3
"""Create/verify an immutable release bundle. Never build, tag, publish, or repair missing files.

The manifest must come from a trusted build/draft Release, not from a fork PR.
Hash verification proves consistency with that manifest, not who produced it.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile

try:
    from . import changelog_tool as changelog
    from . import release_plan as rp
    from . import validate_targets as vt
    from . import verify_artifacts as va
except ImportError:
    import changelog_tool as changelog
    import release_plan as rp
    import validate_targets as vt
    import verify_artifacts as va

MANIFEST = 'release-manifest.json'
PLAN = 'release-plan.json'
PROPERTIES = 'gradle.properties'
MANIFEST_KEYS = {'schema_version', 'plan', 'provenance', 'properties', 'artifacts', 'notes'}
FILE_KEYS = {'filename', 'size', 'sha256', 'sha512'}


class BundleError(ValueError):
    """Bundle is missing, changed, or does not match the fixed source/plan."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise BundleError(message)


def canonical_json(value: dict) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + '\n'


def _load_json(path: pathlib.Path) -> dict:
    return json.loads(path.read_text(encoding='utf-8'), object_pairs_hook=vt._unique_object)


def _file_info(path: pathlib.Path) -> dict:
    require(path.is_file() and not path.is_symlink(), f'missing/unsafe file: {path.name}')
    raw = path.read_bytes()
    return {'filename': path.name, 'size': len(raw), 'sha256': hashlib.sha256(raw).hexdigest(),
            'sha512': hashlib.sha512(raw).hexdigest()}


def _file_record(record: dict) -> None:
    require(isinstance(record, dict) and set(record) == FILE_KEYS, 'invalid file record')
    filename = record['filename']
    require(isinstance(filename, str) and re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9.+_-]*', filename) is not None,
            'unsafe filename')
    require(type(record['size']) is int and record['size'] > 0, 'invalid file size')
    for algorithm, length in [('sha256', 64), ('sha512', 128)]:
        require(isinstance(record[algorithm], str) and re.fullmatch(rf'[0-9a-f]{{{length}}}', record[algorithm]) is not None,
                f'invalid {algorithm}')


def _provenance(value: dict, plan: dict) -> None:
    keys = {'source_sha', 'java', 'gradle_jvm', 'gradle_version', 'plugins', 'dependencies', 'inputs', 'workflow', 'build_checks'}
    require(isinstance(value, dict) and set(value) == keys, 'invalid provenance fields')
    require(value['source_sha'] == plan['source_sha'], 'provenance source SHA mismatch')
    checks = {':fabric:test'} | {f":{row['loader']}:{task}" for row in plan['targets'] for task in ['build', 'check']}
    require(value['build_checks'] == sorted(checks), 'missing/extra successful build/check gates')
    java = value['java']
    require(isinstance(java, dict) and set(java) == {'major', 'runtime', 'vendor'}, 'invalid Java provenance')
    require(type(java['major']) is int and java['major'] == plan['targets'][0]['java_version'], 'Java major mismatch')
    for key in ['runtime', 'vendor']:
        require(isinstance(java[key], str) and bool(java[key].strip()), f'missing actual Java {key}')
    require(re.match(rf"{java['major']}(?:[.+-]|$)", java['runtime']) is not None, 'Java runtime/major mismatch')
    jvm = value['gradle_jvm']
    require(isinstance(jvm, dict) and set(jvm) == {'major', 'runtime', 'vendor'}
            and type(jvm['major']) is int and jvm['major'] > 0, 'invalid Gradle JVM provenance')
    require(all(isinstance(jvm[k], str) and bool(jvm[k].strip()) for k in ['runtime', 'vendor']), 'missing Gradle JVM runtime')
    require(re.match(rf"{jvm['major']}(?:[.+-]|$)", jvm['runtime']) is not None, 'Gradle JVM runtime/major mismatch')
    require(isinstance(value['gradle_version'], str)
            and re.fullmatch(r'[0-9]+\.[0-9]+(?:\.[0-9]+)?', value['gradle_version']) is not None, 'invalid Gradle version')
    require(isinstance(value['plugins'], dict) and bool(value['plugins']), 'missing plugin versions')
    for plugin, version in value['plugins'].items():
        require(isinstance(plugin, str) and isinstance(version, str) and bool(version)
                and not re.search(r'SNAPSHOT|[+\[\]()*,]|latest\.', version, re.I), 'mutable plugin version')
    require(isinstance(value['inputs'], dict)
            and set(value['inputs']) == {'wrapper_sha256', 'verification_metadata_sha256'}, 'missing input hashes')
    for digest in value['inputs'].values():
        require(isinstance(digest, str) and re.fullmatch(r'[0-9a-f]{64}', digest) is not None, 'invalid input hash')
    workflow = value['workflow']
    require(isinstance(workflow, dict) and set(workflow) == {'repository', 'run_id', 'run_attempt'}, 'invalid workflow run')
    require(workflow['repository'] == 'aktnb/stream-tweaks', 'wrong workflow repository')
    require(all(isinstance(workflow[k], str) and re.fullmatch(r'[1-9][0-9]*', workflow[k])
                for k in ['run_id', 'run_attempt']), 'invalid workflow run identity')
    deps = value['dependencies']
    require(isinstance(deps, list) and bool(deps), 'missing resolved dependencies')
    seen, loaders = set(), {row['loader'] for row in plan['targets']}
    for dep in deps:
        require(isinstance(dep, dict) and set(dep) == {'loader', 'configuration', 'module', 'filename', 'sha256'},
                'invalid resolved dependency')
        require(dep['loader'] in loaders and dep['configuration'] in {'compileClasspath', 'runtimeClasspath'},
                'unexpected dependency target/configuration')
        require(all(isinstance(dep[k], str) and bool(dep[k]) for k in ['module', 'filename', 'sha256']), 'invalid dependency')
        require(re.fullmatch(r'[0-9a-f]{64}', dep['sha256']) is not None, 'invalid dependency hash')
        key = tuple(dep[k] for k in ['loader', 'configuration', 'module', 'filename'])
        require(key not in seen, 'duplicate dependency')
        seen.add(key)
    require({dep['loader'] for dep in deps} == loaders, 'missing target dependency provenance')


def collect_provenance(root: pathlib.Path, resolved: dict, plan: dict, *, run_id: str, run_attempt: str,
                       repository: str = 'aktnb/stream-tweaks') -> dict:
    """Use the actual Gradle/JVM resolution report, not all verification-metadata entries."""
    require(set(resolved) == {'gradle_version', 'java', 'gradle_jvm', 'dependencies', 'build_checks'}, 'invalid Gradle resolution report')
    build = (root / 'build.gradle').read_text(encoding='utf-8')
    plugins = dict(re.findall(r"id\s+'([^']+)'\s+version\s+'([^']+)'", build))
    wrapper = vt.load_properties(root / 'gradle/wrapper/gradle-wrapper.properties')
    require(f"gradle-{resolved['gradle_version']}-bin.zip" in wrapper['distributionUrl'], 'Gradle/wrapper mismatch')
    result = dict(copy.deepcopy(resolved), source_sha=plan['source_sha'], plugins=plugins,
                  inputs={'wrapper_sha256': wrapper['distributionSha256Sum'],
                          'verification_metadata_sha256': hashlib.sha256(
                              (root / 'gradle/verification-metadata.xml').read_bytes()).hexdigest()},
                  workflow={'repository': repository, 'run_id': run_id, 'run_attempt': run_attempt})
    _provenance(result, plan)
    return result


def generate_notes(text: str, plan: dict) -> dict[str, str]:
    """Use existing changelog parsing/filtering; never update CHANGELOG or allocate a version."""
    _, sections = changelog.parse_sections(text)
    section = plan['mod_version'] if plan['channel'] == 'release' else 'Unreleased'
    matches = [body for name, _, body in sections if name.lower() == section.lower()]
    require(len(matches) == 1, f'missing/duplicate changelog section: {section}')
    loaders = {row['loader'] for row in plan['targets']}
    result = {}
    for name, selection in [('combined', loaders)] + [(loader, {loader}) for loader in sorted(loaders)]:
        notes = changelog.filter_section_body(matches[0], frozenset(selection), strip_tags=len(selection) == 1).strip()
        result[f'notes-{name}.md'] = (notes or changelog.NO_CHANGES_NOTE) + '\n'
    return result


def verify_bundle(directory: str | pathlib.Path, *, expected_source_sha: str,
                  expected_plan: dict | None = None, expected_manifest_sha256: str | None = None) -> dict:
    """Publication-side gate. Missing/extra/altered files cause a stop, never a rebuild."""
    directory = pathlib.Path(directory)
    require(directory.is_dir() and not directory.is_symlink(), 'missing/unsafe bundle directory')
    require(re.fullmatch(r'[0-9a-f]{40}', expected_source_sha) is not None, 'expected full source SHA required')
    manifest_path = directory / MANIFEST
    digest = _file_info(manifest_path)['sha256']
    if expected_manifest_sha256 is not None:
        require(digest == expected_manifest_sha256, 'manifest hash mismatch')
    manifest = _load_json(manifest_path)
    require(isinstance(manifest, dict) and set(manifest) == MANIFEST_KEYS, 'invalid manifest fields')
    require(type(manifest['schema_version']) is int and manifest['schema_version'] == 1, 'unsupported manifest schema')
    plan = rp.validate_plan(manifest['plan'])
    require(plan['source_sha'] == expected_source_sha, 'bundle source SHA mismatch')
    if expected_plan is not None:
        require(plan == rp.validate_plan(expected_plan), 'bundle fixed plan mismatch')
    _provenance(manifest['provenance'], plan)
    plan_path = directory / PLAN
    require(plan_path.is_file() and not plan_path.is_symlink(), 'missing/unsafe saved plan')
    require(_load_json(plan_path) == plan and plan_path.read_text(encoding='utf-8') == rp.canonical_json(plan),
            'saved plan mismatch/noncanonical content')
    props_record = manifest['properties']
    _file_record(props_record)
    require(props_record['filename'] == PROPERTIES, 'unexpected properties file')
    require(_file_info(directory / PROPERTIES) == props_record, 'properties hash mismatch')
    props = vt.load_properties(directory / PROPERTIES)
    rows = [dict(row, minecraft=plan['minecraft'], source_branch=plan['source_branch']) for row in plan['targets']]
    vt.validate_properties(rows, props, source_branch=plan['source_branch'])
    require(props['mod_version'] == plan['mod_version'], 'properties release version mismatch')
    artifacts = manifest['artifacts']
    require(isinstance(artifacts, list) and len(artifacts) == len(plan['targets']), 'missing/extra target artifact')
    for artifact, target in zip(artifacts, plan['targets']):
        require(isinstance(artifact, dict) and artifact.get('target_id') == target['id'], 'artifact target mismatch')
        require(artifact.get('filename') == target['filename'], 'unexpected normal JAR filename')
        require(not (directory / target['filename']).is_symlink(), 'unsafe artifact symlink')
        verified = va.verify_artifact(directory / target['filename'], target['loader'], props)
        require(artifact == dict(verified, target_id=target['id']), 'artifact contents/hash mismatch')
    notes = manifest['notes']
    expected_notes = {'notes-combined.md'} | {f"notes-{row['loader']}.md" for row in plan['targets']}
    require(isinstance(notes, list) and len(notes) == len(expected_notes), 'missing/extra notes')
    names = []
    for note in notes:
        _file_record(note)
        require(note['filename'] in expected_notes, 'unexpected note filename')
        require(_file_info(directory / note['filename']) == note, 'notes hash mismatch')
        names.append(note['filename'])
    require(set(names) == expected_notes, 'duplicate/missing notes')
    expected_files = {MANIFEST, PLAN, PROPERTIES} | expected_notes | {row['filename'] for row in plan['targets']}
    require({path.name for path in directory.iterdir()} == expected_files, 'missing/extra bundle files')
    return copy.deepcopy(manifest)


def create_bundle(plan: dict, *, root: str | pathlib.Path, output: str | pathlib.Path,
                  provenance: dict, expected_source_sha: str) -> dict:
    """Publish a directory atomically only after every target has passed validation."""
    plan, root, output = rp.validate_plan(plan), pathlib.Path(root), pathlib.Path(output)
    require(plan['source_sha'] == expected_source_sha, 'build source SHA mismatch')
    require(not output.exists(), 'bundle already exists; verify/reuse it instead of overwriting')
    _provenance(provenance, plan)
    props = vt.load_properties(root / PROPERTIES) | {'mod_version': plan['mod_version']}
    notes = generate_notes((root / 'CHANGELOG.md').read_text(encoding='utf-8'), plan)
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.release-bundle-', dir=output.parent) as temporary:
        stage = pathlib.Path(temporary) / 'bundle'
        stage.mkdir()
        (stage / PLAN).write_text(rp.canonical_json(plan), encoding='utf-8')
        (stage / PROPERTIES).write_text(''.join(f'{key}={value}\n' for key, value in sorted(props.items())), encoding='utf-8')
        for name, content in notes.items():
            (stage / name).write_text(content, encoding='utf-8')
        artifacts = []
        for target in plan['targets']:
            source = va.select_artifact(root / target['loader'] / 'build/libs', target['loader'], props)
            require(not source.is_symlink(), 'unsafe source artifact symlink')
            verified = va.verify_artifact(source, target['loader'], props)
            require(verified['filename'] == target['filename'], 'plan artifact filename mismatch')
            shutil.copyfile(source, stage / source.name)
            artifacts.append(dict(verified, target_id=target['id']))
        manifest = {'schema_version': 1, 'plan': plan, 'provenance': copy.deepcopy(provenance),
                    'properties': _file_info(stage / PROPERTIES), 'artifacts': artifacts,
                    'notes': [_file_info(stage / name) for name in sorted(notes)]}
        (stage / MANIFEST).write_text(canonical_json(manifest), encoding='utf-8')
        verify_bundle(stage, expected_source_sha=expected_source_sha, expected_plan=plan)
        require(not output.exists(), 'bundle output appeared during creation')
        stage.rename(output)
    return manifest


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='mode', required=True)
    create = sub.add_parser('create', help='After successful build/check, validate and save all targets')
    create.add_argument('--plan', required=True, type=pathlib.Path)
    create.add_argument('--root', type=pathlib.Path, default=pathlib.Path('.'))
    create.add_argument('--resolved-provenance', required=True, type=pathlib.Path)
    create.add_argument('--output', required=True, type=pathlib.Path)
    create.add_argument('--run-id', required=True)
    create.add_argument('--run-attempt', required=True)
    create.add_argument('--repository', default='aktnb/stream-tweaks')
    verify = sub.add_parser('verify', help='Verify a trusted saved bundle without rebuilding')
    verify.add_argument('--bundle', required=True, type=pathlib.Path)
    verify.add_argument('--plan', type=pathlib.Path)
    verify.add_argument('--manifest-sha256')
    for command in [create, verify]:
        command.add_argument('--source-sha', required=True, help='Expected immutable source SHA from trusted workflow/tag')
    args = parser.parse_args(argv)
    try:
        if args.mode == 'create':
            # The public API supports fixture roots; the operational CLI additionally proves checkout identity.
            actual = subprocess.run(['git', '-C', str(args.root), 'rev-parse', 'HEAD'], check=True,
                                    capture_output=True, text=True).stdout.strip()
            require(actual == args.source_sha, 'checkout HEAD differs from fixed source SHA')
            dirty = subprocess.run(['git', '-C', str(args.root), 'status', '--porcelain', '--untracked-files=normal'],
                                   check=True, capture_output=True, text=True).stdout
            require(not dirty, 'release source checkout has uncommitted or untracked files')
            plan = rp.load_plan(args.plan)
            provenance = collect_provenance(args.root, _load_json(args.resolved_provenance), plan,
                                            run_id=args.run_id, run_attempt=args.run_attempt, repository=args.repository)
            result = create_bundle(plan, root=args.root, output=args.output, provenance=provenance,
                                   expected_source_sha=args.source_sha)
        else:
            result = verify_bundle(args.bundle, expected_source_sha=args.source_sha,
                                   expected_plan=rp.load_plan(args.plan) if args.plan else None,
                                   expected_manifest_sha256=args.manifest_sha256)
        sys.stdout.write(canonical_json(result))
    except (BundleError, rp.PlanError, vt.ValidationError, OSError, ValueError, KeyError,
            subprocess.CalledProcessError) as exc:
        print(f'release bundle failed: {exc}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
