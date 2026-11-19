from hashlib import sha256
from io import BytesIO
import json
from pathlib import Path
import tempfile
import tomllib
import unittest
from unittest.mock import patch
from zipfile import ZipFile

from mcmodup import NEOFORGE_VERSION
from mcmodup.inputs import InputError
from mcmodup.legacy_testbed import AUDIT, COMPONENTS, METADATA, OriginalArchive, _combine, build_testbed, fetch_originals
from mcmodup.method_ports import PROVENANCE
from mcmodup.port import _metadata
from mcmodup.audit import ModMetadata


TAG = "data/minecraft/tags/block/mineable/pickaxe.json"


class LegacyTestbedTests(unittest.TestCase):
    def make_parts(self, root, overrides=None):
        outputs = {}
        for index, (mod, version) in enumerate(sorted(COMPONENTS.items())):
            path = root / (mod + ".jar")
            outputs[mod] = path
            files = {
                METADATA: _metadata(ModMetadata(""), mod, mod, version, "test").replace(f"[{NEOFORGE_VERSION},21.2)", f"[{NEOFORGE_VERSION}]"),
                PROVENANCE: json.dumps({"mod": mod}),
                "META-INF/MANIFEST.MF": "Manifest-Version: 1.0\r\n\r\n",
                "pack.mcmeta": "{}",
                TAG: json.dumps({"replace": False, "values": ["minecraft:stone", f"{mod}:example"]}),
            }
            files.update((overrides or {}).get(index, {}))
            with ZipFile(path, "w") as archive:
                for name, data in files.items():
                    archive.writestr(name, data)
        return outputs

    def test_flat_jar_has_distinct_ids_shared_tag_and_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            parts = self.make_parts(root)
            first, second = root / "one.jar", root / "two.jar"
            audit = {"parity_verified": False, "private_use_only": True}
            _combine(parts, audit, first)
            _combine(dict(reversed(list(parts.items()))), audit, second)
            self.assertEqual(first.read_bytes(), second.read_bytes())
            with ZipFile(first) as jar:
                meta = tomllib.loads(jar.read(METADATA).decode())
                self.assertEqual({row["modId"]: row["version"] for row in meta["mods"]}, COMPONENTS)
                self.assertEqual(json.loads(jar.read(AUDIT)), audit)
                self.assertEqual(json.loads(jar.read(TAG)), {"replace": False, "values": sorted({
                    "minecraft:stone", *(f"{mod}:example" for mod in COMPONENTS)})})
                self.assertFalse(any(name.endswith(".jar") for name in jar.namelist()))
                for mod in COMPONENTS:
                    self.assertEqual(json.loads(jar.read(f"META-INF/mcmodup/{mod}/{Path(PROVENANCE).name}")), {"mod": mod})

    def test_refuses_nested_files_conflicts_and_bad_dependencies_without_output(self):
        for overrides, reason in (
            ({0: {"data/shadow.jar": b"not a dependency"}}, "embedded library"),
            ({0: {"mcmodup/X.class": b"first"}, 1: {"mcmodup/X.class": b"second"}}, "resource collision"),
            ({0: {TAG: '{"replace":true,"values":[]}'}}, "duplicate tag"),
            ({0: {TAG: '{"replace":false,"values":[{"id":"minecraft:stone"}]}'}}, "duplicate tag"),
            ({0: {METADATA: "modLoader = 'other'"}}, "Cannot combine"),
        ):
            with self.subTest(reason=reason), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                parts = self.make_parts(root, overrides)
                with self.assertRaisesRegex(InputError, reason):
                    _combine(parts, {}, root / "blocked.jar")
                self.assertFalse((root / "blocked.jar").exists())

    def test_download_is_bounded_and_mismatch_leaves_no_files(self):
        payload = b"reviewed test fixture"
        pin = OriginalArchive("original.jar", "https://example.invalid/original.jar", sha256(payload).hexdigest(), len(payload))
        with tempfile.TemporaryDirectory() as directory, patch("mcmodup.legacy_testbed.ORIGINALS", (pin,)), \
                patch("mcmodup.legacy_testbed.build_opener") as opener:
            root = Path(directory)
            opener.return_value.open.return_value = BytesIO(b"x" * len(payload))
            with self.assertRaisesRegex(InputError, "Unexpected bytes"):
                fetch_originals(root / "bad")
            self.assertFalse((root / "bad").exists())
            opener.return_value.open.return_value = BytesIO(payload + b"x")
            with self.assertRaisesRegex(InputError, "Size limit exceeded"):
                fetch_originals(root / "too-large")
            self.assertFalse((root / "too-large").exists())
            opener.return_value.open.return_value = BytesIO(payload)
            [download] = fetch_originals(root / "good")
            self.assertEqual(download.read_bytes(), payload)

    def test_refusal_does_not_change_inputs_or_out_path(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            original = root / "wrong.jar"
            original.write_bytes(b"not the pinned original")
            out = root / "new"
            with self.assertRaisesRegex(InputError, "--allow-preview-ports"):
                build_testbed(original, original, original, original, out)
            with self.assertRaisesRegex(InputError, "Wrong or missing exact original"):
                build_testbed(original, original, original, original, out, allow_preview=True)
            self.assertFalse(out.exists())
            self.assertEqual(original.read_bytes(), b"not the pinned original")
            out.mkdir()
            keep = out / "already-there.txt"
            keep.touch()
            with self.assertRaisesRegex(InputError, "Choose a new output directory"):
                build_testbed(original, original, original, original, out, allow_preview=True)
            self.assertEqual(list(out.iterdir()), [keep])


if __name__ == "__main__":
    unittest.main()
