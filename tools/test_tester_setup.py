import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from tester_setup import digest, prepare, setting_text, verify


class TesterFixtureTest(unittest.TestCase):
    def setUp(self):
        build = Path(__file__).resolve().parents[1] / 'build'
        build.mkdir(exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(prefix='tester-setup-test-', dir=build)
        assert Path(self.temp.name).resolve().parent == build.resolve()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.payload = b'fixture artifact'
        self.profile = {
            'id': 'test',
            'artifacts': [{'path': 'mods/fixture.jar', 'url': 'https://example.invalid/fixture.jar',
                           'sha256': hashlib.sha256(self.payload).hexdigest()}],
            'settings': {'occlusion-cull': True, 'sunless-shadow': False},
            'files': {'options.txt': 'preferredGraphicsBackend:"vulkan"\nrenderDistance:16\n',
                      'config/sodium-options.json': '{"quality":{"test":true}}',
                      'vitrail/pack.txt': 'enabled=true\n'}
        }
        self.profile_hash = 'profile-digest'

    def fetch(self, url, path):
        path.write_bytes(self.payload)

    def install(self):
        return prepare(self.root, self.profile, self.profile_hash, fetch=self.fetch)

    def test_prepare_and_game_written_options(self):
        target = self.install()
        self.assertEqual('default', verify(target, self.profile, self.profile_hash))
        (target / 'options.txt').write_text('preferredGraphicsBackend:"vulkan"\nrenderDistance:32\nnewUiKey:0\n')
        self.assertEqual('default', verify(target, self.profile, self.profile_hash))
        (target / 'config/vultrum.properties').write_text(setting_text(self.profile, 'off'))
        self.assertEqual('off', verify(target, self.profile, self.profile_hash))

    def test_existing_directory_preserved(self):
        target = self.root / 'run-compat-tester'
        target.mkdir()
        (target / 'valuable.txt').write_text('keep')
        with self.assertRaisesRegex(ValueError, 'already exists'):
            self.install()
        self.assertEqual('keep', (target / 'valuable.txt').read_text())

    def test_bad_download_not_installed_and_stage_removed(self):
        def broken_fetch(url, path):
            path.write_bytes(b'wrong artifact')
        with self.assertRaisesRegex(ValueError, 'Checksum mismatch'):
            prepare(self.root, self.profile, self.profile_hash, fetch=broken_fetch)
        self.assertEqual([], list(self.root.iterdir()))

    def test_local_artifact_still_verified_without_network(self):
        source = self.root / 'local.jar'
        source.write_bytes(self.payload)
        def no_network(url, path):
            self.fail('A local artifact should not use the network')
        target = prepare(self.root, self.profile, self.profile_hash,
                         {'mods/fixture.jar': source}, fetch=no_network)
        self.assertEqual(digest(source), digest(target / 'mods/fixture.jar'))

    def test_modified_artifact_and_extra_mod_rejected(self):
        target = self.install()
        jar = target / 'mods/fixture.jar'
        jar.write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'Checksum mismatch'):
            verify(target, self.profile, self.profile_hash)
        jar.write_bytes(self.payload)
        (target / 'mods/extra.jar').write_bytes(b'extra')
        with self.assertRaisesRegex(ValueError, 'Unexpected or missing mods'):
            verify(target, self.profile, self.profile_hash)

    def test_profile_setting_and_diagnostic_drift_rejected(self):
        target = self.install()
        with self.assertRaisesRegex(ValueError, 'different fixture profile'):
            verify(target, self.profile, 'changed-profile')
        options = target / 'options.txt'
        options.write_text('preferredGraphicsBackend:"opengl"\nrenderDistance:16\n')
        with self.assertRaisesRegex(ValueError, 'Changed fixture setting'):
            verify(target, self.profile, self.profile_hash)
        options.write_text(self.profile['files']['options.txt'])
        (target / 'vitrail/amortise-shadow').write_text('2')
        with self.assertRaisesRegex(ValueError, 'Unexpected Vitrail control'):
            verify(target, self.profile, self.profile_hash)

    def test_manifest_cannot_write_outside_fixture(self):
        self.profile['files']['../outside.txt'] = 'escape'
        with self.assertRaisesRegex(ValueError, 'escapes its directory'):
            self.install()
        self.assertFalse((self.root / 'outside.txt').exists())
        self.assertEqual([], list(self.root.iterdir()))


if __name__ == '__main__':
    unittest.main()
