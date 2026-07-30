"""Deployment regressions: bound old chunks, forbid stale HTML, detect corrupted/colliding files."""
import json
from pathlib import Path
import tempfile
import unittest
from web_asset_retention import MANIFEST, file_hash, release, retain


class AssetRetentionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def build(self, name, filename=None, content=None):
        root = self.root / name
        (root / 'assets').mkdir(parents=True)
        (root / 'index.html').write_text(name)
        (root / 'assets' / (filename or name + '.js')).write_text(content or name)
        return root

    def test_old_tab_chunks_survive_but_old_html_and_fourth_release_do_not(self):
        previous = self.build('v1')
        for number in (2, 3, 4):
            current = self.build('v' + str(number))
            result = retain(current, previous, current, 'assets')
            previous = current
        self.assertEqual(result['releaseCount'], 3)
        self.assertEqual({p.name for p in (previous / 'assets').iterdir()}, {'v2.js', 'v3.js', 'v4.js'})
        self.assertEqual((previous / 'index.html').read_text(), 'v4')
        again = retain(previous, previous, previous, 'assets')
        self.assertLessEqual(again['releaseCount'], 3)

    def test_same_filename_must_not_identify_different_bytes(self):
        previous = self.build('previous', 'shared.js', 'old')
        current = self.build('current', 'shared.js', 'new')
        with self.assertRaisesRegex(ValueError, 'different bytes'):
            retain(current, previous, current, 'assets')

    def test_manifest_hash_and_actual_bytes_are_verified(self):
        previous = self.build('previous')
        files = {'assets/previous.js': file_hash(previous / 'assets/previous.js')}
        (previous / MANIFEST).write_text(json.dumps({'releases': [release(files)]}))
        (previous / 'assets/previous.js').write_text('corrupted')
        with self.assertRaisesRegex(ValueError, 'differs'):
            retain(self.build('current'), previous, self.root / 'output', 'assets')

    def test_manifest_cannot_retain_html_or_escape_the_asset_directory(self):
        previous = self.build('previous')
        for name in ('index.html', 'assets/../index.html', '/etc/passwd'):
            (previous / MANIFEST).write_text(json.dumps({'releases': [release({name: 'invalid'})]}))
            with self.assertRaisesRegex(ValueError, 'static asset directory'):
                retain(self.build(name.replace('/', '_') + '-current'), previous, self.root / 'output', 'assets')


if __name__ == '__main__':
    unittest.main()
