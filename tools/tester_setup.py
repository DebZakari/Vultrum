"""Prepare and verify the pinned tester fixture without touching a launcher instance."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
PROFILE = ROOT / 'bench/tester-fixture.json'
MARKER = 'tester-fixture.json'


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def fixture_path(base, name):
    destination = base / name
    resolved_base = base.resolve()
    resolved = destination.resolve()
    if resolved == resolved_base or not resolved.is_relative_to(resolved_base):
        raise ValueError(f'Fixture path escapes its directory: {name}')
    return destination


def properties(text, separator='='):
    result = {}
    for line in text.splitlines():
        if not line.strip() or line.lstrip().startswith('#'):
            continue
        key, found, value = line.partition(separator)
        if found:
            result[key.strip()] = value.strip()
    return result


def setting_text(profile, mode):
    if mode not in ('default', 'off'):
        raise ValueError('Settings must be default or off')
    return ''.join(f'{key}={str(value if mode == "default" else False).lower()}\n'
                   for key, value in profile['settings'].items())


def download(url, path):
    request = urllib.request.Request(url, headers={'User-Agent': 'Vultrum-tester-setup/0.1'})
    with urllib.request.urlopen(request, timeout=60) as response, path.open('wb') as out:
        shutil.copyfileobj(response, out)


def prepare(root, profile, profile_hash, local_artifacts=None, fetch=download):
    """Install a new isolated fixture atomically; existing directories are never replaced."""
    root = root.resolve()
    target = root / 'run-compat-tester'
    if target.exists() or target.is_symlink():
        raise ValueError(f'{target} already exists; use --verify rather than overwriting it')
    stage_name = 'run-compat-tester-preparing-' + uuid.uuid4().hex
    stage = root / stage_name
    stage.mkdir()
    try:
        for artifact in profile['artifacts']:
            destination = fixture_path(stage, artifact['path'])
            destination.parent.mkdir(parents=True, exist_ok=True)
            source = (local_artifacts or {}).get(artifact['path'])
            if source:
                shutil.copyfile(source, destination)
            else:
                fetch(artifact['url'], destination)
            if digest(destination) != artifact['sha256']:
                raise ValueError(f'Checksum mismatch: {artifact["path"]}')
        for name, text in profile['files'].items():
            destination = fixture_path(stage, name)
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_text(text, encoding='utf-8', newline='\n')
        (stage / 'config/vultrum.properties').write_text(setting_text(profile, 'default'), encoding='utf-8')
        (stage / MARKER).write_text(json.dumps({'profile': profile['id'], 'sha256': profile_hash}, indent=2), encoding='utf-8')
        verify(stage, profile, profile_hash)
        # On Windows a directory rename fails when the destination already exists.
        if target.exists() or target.is_symlink():
            raise ValueError('The tester directory appeared during preparation')
        stage.rename(target)
    finally:
        if stage.exists():
            # Check the exact resolved recursive-delete target, including junctions, before cleanup.
            if stage.name != stage_name or stage.parent.resolve() != root or stage.resolve() != stage.absolute():
                raise ValueError('Refusing cleanup outside the created staging directory')
            shutil.rmtree(stage)
    return target


def verify(target, profile, profile_hash):
    if target.resolve() != target.absolute():
        raise ValueError('Tester directory must not be a symbolic link or junction')
    marker = json.loads((target / MARKER).read_text(encoding='utf-8'))
    if marker != {'profile': profile['id'], 'sha256': profile_hash}:
        raise ValueError('The tester directory belongs to a different fixture profile')
    expected_mods = {Path(a['path']).name for a in profile['artifacts'] if a['path'].startswith('mods/')}
    actual_mods = {p.name for p in (target / 'mods').glob('*.jar')}
    if expected_mods != actual_mods:
        raise ValueError('Unexpected or missing mods in the tester directory')
    for artifact in profile['artifacts']:
        if digest(fixture_path(target, artifact['path'])) != artifact['sha256']:
            raise ValueError(f'Checksum mismatch: {artifact["path"]}')
    for name, expected_text in profile['files'].items():
        text = fixture_path(target, name).read_text(encoding='utf-8-sig')
        if name.endswith('sodium-options.json'):
            expected, actual = json.loads(expected_text), json.loads(text)
        elif name == 'options.txt':
            expected, actual = properties(expected_text, ':'), properties(text, ':')
            # The scenario controls render distance. Minecraft also writes unrelated default/UI keys.
            expected.pop('renderDistance', None)
        else:
            expected, actual = properties(expected_text), properties(text)
            if actual != expected:
                raise ValueError(f'Changed fixture setting: {name}')
        for key, value in expected.items():
            if actual.get(key) != value:
                raise ValueError(f'Changed fixture setting: {name}:{key}')
    for name in ('amortise-shadow', 'full-pass-barrier', 'frame-constants', 'options.txt', 'graphics-api.txt'):
        if (target / 'vitrail' / name).exists():
            raise ValueError(f'Unexpected Vitrail control: {name}')
    controls = target / 'vultrum'
    if controls.exists() and any(p.is_file() for p in controls.iterdir()):
        raise ValueError('Unexpected Vultrum diagnostic control')
    current = properties((target / 'config/vultrum.properties').read_text(encoding='utf-8-sig'))
    for mode in ('default', 'off'):
        if current == properties(setting_text(profile, mode)):
            return mode
    raise ValueError('Vultrum settings are neither the default arm nor the off arm')


def source_check(root, profile):
    files = ['src/main', 'build.gradle', 'gradle.properties', 'gradle/wrapper', 'gradlew', 'gradlew.bat']
    result = subprocess.run(['git', 'diff', '--exit-code', profile['sourceTag'], '--', *files],
                            cwd=root, capture_output=True, text=True)
    if result.returncode:
        raise ValueError('Runtime/build source differs from the pinned alpha.5 tag; use the documented tester checkout')
    untracked = subprocess.run(['git', 'ls-files', '--others', '--exclude-standard', '--', *files],
                               cwd=root, capture_output=True, text=True)
    if untracked.returncode or untracked.stdout.strip():
        raise ValueError('Untracked runtime/build files would change the tester implementation')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--prepare', action='store_true')
    mode.add_argument('--verify', action='store_true')
    mode.add_argument('--settings', choices=['default', 'off'])
    parser.add_argument('--shader-pack', type=Path, help='Use a local copy instead of downloading it')
    parser.add_argument('--vitrail-jar', type=Path, help='Use a local copy instead of downloading it')
    args = parser.parse_args()
    profile = json.loads(PROFILE.read_text(encoding='utf-8'))
    profile_hash = digest(PROFILE)
    source_check(ROOT, profile)
    target = ROOT / 'run-compat-tester'
    if args.prepare:
        sources = {}
        for artifact in profile['artifacts']:
            source = args.vitrail_jar if artifact['path'].startswith('mods/') else args.shader_pack
            if source:
                sources[artifact['path']] = source.resolve()
        prepare(ROOT, profile, profile_hash, sources)
    else:
        verify(target, profile, profile_hash)
        if args.settings:
            (target / 'config/vultrum.properties').write_text(setting_text(profile, args.settings), encoding='utf-8')
    print(f'Verified {profile["id"]}: {target} ({verify(target, profile, profile_hash)} settings)')


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        raise SystemExit(str(error))
