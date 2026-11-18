import json
from pathlib import Path
import tempfile
import unittest
from zipfile import ZipFile

from mcmodup.inputs import InputError
from mcmodup.source_ports import digest
from mcmodup.tinkerer_computercraft import Pin, merge_archive, merge_file


class ComputerCraftSourceTests(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name)

    def archive(self, letter, files):
        ref = letter * 40
        root = f"Thaumic-Tinkerer-1.21.1-{ref}"
        path = self.root / (root + ".zip")
        with ZipFile(path, "x") as archive:
            for name, data in files.items():
                archive.writestr(f"{root}/{name}", data)
        return Pin("Thaumic-Tinkerer-1.21.1", ref, digest(path))

    def test_backport_keeps_baseline_fixes_and_limits_delta_to_reviewed_paths(self):
        base = {"api.java": "start\nold\nbody\nmore\nend\nold footer\n", "assets/model": "baseline asset"}
        ancestor = self.archive("a", base)
        current = self.archive("b", {**base, "api.java": base["api.java"].replace("old footer", "fixed footer"),
                                     "regression.java": "keep the existing regression"})
        feature = self.archive("c", {"api.java": base["api.java"].replace("\nold\n", "\nnew\n"),
                                     "cc.java": "the integration", "assets/model": "must not be copied"})
        output = self.root / "pair.zip"
        merge_archive((current, ancestor, feature), self.root, False, output, lambda n: n.endswith(".java"))
        with ZipFile(output) as archive:
            self.assertEqual(archive.read(f"{current.root}/api.java"), b"start\nnew\nbody\nmore\nend\nfixed footer\n")
            self.assertEqual(archive.read(f"{current.root}/cc.java"), b"the integration")
            self.assertEqual(archive.read(f"{current.root}/assets/model"), b"baseline asset")
            self.assertIn(f"{current.root}/regression.java", archive.namelist())

    def test_archive_fingerprint_mismatch_is_rejected_before_merge(self):
        pin = self.archive("a", {"api.java": "original"})
        archive = self.root / (pin.root + ".zip")
        with archive.open("ab") as stream:
            stream.write(b"changed")
        with self.assertRaisesRegex(InputError, "exact pinned source"):
            merge_archive((pin, pin, pin), self.root, False, self.root / "no.zip", lambda n: True)
        self.assertFalse((self.root / "no.zip").exists())

    def test_text_conflicts_stop_instead_of_overwriting_release_changes(self):
        with self.assertRaisesRegex(InputError, "does not merge cleanly: api.java"):
            merge_file("api.java", b"existing fix\n", b"initial code\n", b"new code\n", self.root)
        with self.assertRaisesRegex(InputError, "add/delete conflict"):
            merge_file("api.java", b"existing fix\n", b"initial code\n", None, self.root)

    def test_book_translations_merge_by_key_without_downgrading_interface_text(self):
        file = "lang-extra/tile_blocks.en_us.json"
        old = b'{"interface": "old power text", "obsolete": "old"}'
        current = b'{"interface": "power passes both ways", "obsolete": "old", "later": "retained"}'
        incoming = b'{"interface": "old power text", "connector": "works with CC: Tweaked"}'
        expected = {"interface": "power passes both ways", "later": "retained", "connector": "works with CC: Tweaked"}
        self.assertEqual(json.loads(merge_file(file, current, old, incoming, self.root)), expected)
        with self.assertRaisesRegex(InputError, "Conflicting language key 'interface'"):
            merge_file(file, current, old, b'{"interface":"conflicting edit"}', self.root)
