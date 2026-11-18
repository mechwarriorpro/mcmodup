"""Build the built-in ports for exact legacy releases, with no binary fallback."""

from dataclasses import replace
import json
from pathlib import Path
import shutil
import tempfile

from . import native_pack
from .inputs import InputError
from .source_ports import BuildOptions, SourcePort, _source_zip, digest, load_ports, require_jdk
from .tinkerer_port import TC_RECIPE, TT_LEGACY_HASH


def _stage_stock_recipe(port: SourcePort, directory: Path, inputs: Path | None) -> Path:
    directory.mkdir(parents=True, exist_ok=False)
    recipe = directory / TC_RECIPE.name
    try:
        if inputs is None:
            shutil.copyfile(TC_RECIPE, recipe)
        else:
            archive = port.source_root + ".zip"
            _source_zip(replace(port, archive=inputs / archive), directory / archive)
            manifest = json.loads(TC_RECIPE.read_text(encoding="utf-8"))
            row = manifest["ports"][0]
            del row["source_url"]
            row["source_zip"] = archive
            recipe.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    except BaseException:
        shutil.rmtree(directory, ignore_errors=True)
        raise
    return recipe


def build_jars(source: Path, target: Path, selections: list[str], output: Path, *,
               allow_preview: bool = False, timeout: int = 1200, source_zips: Path | None = None,
               keep_recipe: Path | None = None) -> Path:
    if not allow_preview:
        raise InputError("No built-in port has full parity yet. Use --allow-preview-ports only if you want the incomplete native builds.")
    if not selections:
        raise InputError("Select at least one original archive with --mod.")
    base_options = BuildOptions(TC_RECIPE, allow_preview, timeout)
    [base] = load_ports(base_options).values()
    state = native_pack.inventory(source, target)
    output = native_pack.stage_destination(state, output)
    if keep_recipe is not None:
        keep_recipe = native_pack.stage_destination(state, keep_recipe)
        if output.is_relative_to(keep_recipe) or keep_recipe.is_relative_to(output):
            raise InputError("--keep-recipe and --output must be separate new directories.")
    if source_zips is not None:
        source_zips = source_zips.expanduser().resolve()
        if not source_zips.is_dir():
            raise InputError("--source-zips must be a directory of exact pinned source ZIPs.")
    addons = native_pack.selected_addons(state, sorted(set(selections)))
    expected = {base.legacy_sha256: {"thaumcraft"}, TT_LEGACY_HASH: {"thaumictinkerer"}}
    problems = list(state.target_issues)
    seen = set()
    for label in sorted(set(selections) | addons.keys()):
        entry = state.entries[label]
        if entry.problem:
            problems.append(f"{label!r}: {entry.problem}")
            continue
        checksum = digest(entry.path)
        if checksum not in expected or state.native_ids(entry) != expected[checksum]:
            problems.append(f"{label!r}: no built-in native source recipe for this exact legacy archive.")
        elif checksum in seen:
            problems.append(f"{label!r}: duplicate original archive in the selection.")
        seen.add(checksum)
    if TT_LEGACY_HASH in seen and base.legacy_sha256 not in seen:
        problems.append("Select the original Thaumcraft 4.2.3.5 JAR to build it together with Thaumic Tinkerer 2.13.0.")
    if not native_pack.matches(state.target_platform.neoforge, base.neoforge):
        problems.append(f"The built-in ports need NeoForge {base.neoforge}; target has {state.target_platform.neoforge}.")
    if problems:
        raise InputError("Cannot build the selected mods:\n- " + "\n- ".join(problems))

    paired = TT_LEGACY_HASH in seen
    require_jdk()
    with tempfile.TemporaryDirectory(prefix="mcmodup-builtin-ports-") as directory:
        prepared = keep_recipe or Path(directory).resolve() / "recipe"
        if paired:
            from .tinkerer_computercraft import prepare

            manifest = prepare(prepared, source_zips)
        elif keep_recipe is not None or source_zips is not None:
            manifest = _stage_stock_recipe(base, prepared, source_zips)
        else:
            manifest = TC_RECIPE
        update = native_pack.plan(native_pack.with_source_ports(state, replace(base_options, manifest=manifest)), selections)
        if update.problems:
            raise InputError("Cannot build the selected mods:\n- " + "\n- ".join(update.problems))
        return native_pack.stage(update, output)
