"""Inspect exact Thaumcraft and Tinkerer assets without publishing or running the old JARs."""

import argparse
from collections import defaultdict
import hashlib
import json
from pathlib import Path
import tempfile

from mcmodup.inputs import MOD_ARCHIVE_LIMIT, open_input
from mcmodup.source_ports import BuildOptions, _unzip, digest, is_legacy_asset, load_ports, prepare_assets


def fingerprint(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


def analyze(original: Path, archive: Path, recipe: Path) -> dict:
    port = load_ports(BuildOptions(recipe)).get(digest(original))
    if port is None or digest(archive) != port.source_sha256:
        raise ValueError("Use the exact original JAR and pinned source ZIP from the recipe.")
    with tempfile.TemporaryDirectory(prefix="mcmodup-asset-audit-") as scratch, open_input(original) as old:
        workspace = Path(scratch) / "project"
        _unzip(archive, workspace, port.source_root)
        converted, locale_rows = prepare_assets(old, workspace, port)
        explicit = port.asset_map | converted
        modern = {}
        for module in ("common", "neoforge"):
            root = workspace / module / "src/main/resources"
            modern.update({p.relative_to(root).as_posix(): p for p in (root / "assets").rglob("*") if p.is_file()})
        hashes = defaultdict(list)
        for name, path in modern.items():
            hashes[digest(path)].append(name)
        kept = 0
        missing = []
        asset_names = [name for name in old.files if is_legacy_asset(name)]
        if absent := set(explicit) - set(asset_names):
            raise ValueError(f"The exact release does not contain mapped resource paths: {sorted(absent)}")
        mapped = 0
        for name in asset_names:
            data = old.read(name, MOD_ARCHIVE_LIMIT)
            sha256 = fingerprint(data)
            if name in explicit:
                target, expected = explicit[name]
                if target in hashes[expected or sha256]:
                    mapped += 1
                    continue
            elif name in hashes[sha256]:
                kept += 1
                continue
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
        return {"exact_asset_count": len(asset_names), "retained_in_active_source": kept, "checked_mappings": mapped,
                "converted_locales": len(converted), "unmapped": missing,
                "unsupported_locale_lines": sum(r["unsupported_lines"] for r in locale_rows),
                "duplicate_locale_entries": sum(r["duplicate_entries"] for r in locale_rows),
                "other_resource_paths": sorted(name for name in old.files
                    if not is_legacy_asset(name) and not name.endswith(".class") and not name.startswith("META-INF/"))}


if __name__ == "__main__":
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("original", type=Path)
    cli.add_argument("archive", type=Path)
    cli.add_argument("recipe", type=Path)
    cli.add_argument("--annotations", action="store_true")
    cli.add_argument("--require-coverage", action="store_true", help="fail on unmapped or mismatched assets")
    args = cli.parse_args()
    result = analyze(args.original, args.archive, args.recipe)
    print(json.dumps(result, indent=2))
    rows = result.pop("unmapped")
    if args.annotations:
        # GitHub Actions retains only ten notice annotations per step.
        size = max(6, (len(rows) + 8) // 9)
        for start in range(0, len(rows), size):
            details = json.dumps(rows[start:start + size], separators=(",", ":")).replace("%", "%25")
            print(f"::notice title=Exact legacy resource comparison::{details}")
        print("::notice title=Exact legacy resource summary::" + json.dumps(result, separators=(",", ":")))
    if args.require_coverage and rows:
        raise SystemExit(f"{len(rows)} legacy asset paths still need a reviewed mapping or import.")
