"""Inspect exact Thaumcraft assets without distributing any files or executing the old JAR."""

import argparse
from collections import defaultdict
import hashlib
import json
from pathlib import Path
import tempfile

from mcmodup.inputs import MOD_ARCHIVE_LIMIT, open_input
from mcmodup.source_ports import BuildOptions, _import_locales, _unzip, digest, load_ports


def fingerprint(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


def analyze(original: Path, archive: Path, recipe: Path) -> dict:
    [port] = load_ports(BuildOptions(recipe)).values()
    if digest(original) != port.legacy_sha256 or digest(archive) != port.source_sha256:
        raise ValueError("Use the exact original JAR and pinned source ZIP from the recipe.")
    with tempfile.TemporaryDirectory(prefix="mcmodup-asset-audit-") as scratch, open_input(original) as old:
        workspace = Path(scratch) / "project"
        _unzip(archive, workspace, port.source_root)
        converted, _ = _import_locales(old, workspace, port)
        modern = {}
        for module in ("common", "neoforge"):
            root = workspace / module / "src/main/resources"
            modern.update({p.relative_to(root).as_posix(): p for p in (root / "assets").rglob("*") if p.is_file()})
        hashes = defaultdict(list)
        for name, path in modern.items():
            hashes[digest(path)].append(name)
        kept = 0
        missing = []
        asset_names = [name for name in old.files if name.startswith("assets/") and name.count("/") >= 2]
        for name in asset_names:
            data = old.read(name, MOD_ARCHIVE_LIMIT)
            sha256 = fingerprint(data)
            if name in hashes[sha256]:
                kept += 1
            elif name not in converted:
                normalized = fingerprint(data.replace(b"\r\n", b"\n"))
                missing.append({"original": name, "original_bytes": len(data),
                                "original_sha256": sha256, "modern_sha256": digest(modern[name]) if name in modern else None,
                                "byte_matches": sorted(hashes[sha256])[:8],
                                "lf_matches": sorted(hashes[normalized])[:8] if normalized != sha256 else [],
                                "direct_references_in_legacy_classes": []})
        queries = {row["original"]: row for row in missing}
        for name in old.files:
            if name.endswith(".class"):
                code = old.read(name)
                for path, row in queries.items():
                    part = path.split("/", 2)[-1]
                    if part.encode() in code or ("/shader/" in path and Path(part).name.encode() in code):
                        row["direct_references_in_legacy_classes"].append(name)
        return {"exact_asset_count": len(asset_names), "retained_in_active_source": kept,
                "converted_locales": len(converted), "unmapped": missing,
                "other_resource_paths": sorted(name for name in old.files
                    if not name.endswith(".class") and not name.startswith(("assets/", "META-INF/")))}


if __name__ == "__main__":
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("original", type=Path)
    cli.add_argument("archive", type=Path)
    cli.add_argument("recipe", type=Path)
    cli.add_argument("--annotations", action="store_true")
    args = cli.parse_args()
    result = analyze(args.original, args.archive, args.recipe)
    print(json.dumps(result, indent=2))
    if args.annotations:
        # GitHub Actions retains only ten notice annotations per step.
        rows = result.pop("unmapped")
        for start in range(0, len(rows), max(6, (len(rows) + 8) // 9)):
            size = max(6, (len(rows) + 8) // 9)
            details = json.dumps(rows[start:start + size], separators=(",", ":")).replace("%", "%25")
            print(f"::notice title=Exact legacy resource comparison::{details}")
        print("::notice title=Exact legacy resource summary::" + json.dumps(result, separators=(",", ":")))
