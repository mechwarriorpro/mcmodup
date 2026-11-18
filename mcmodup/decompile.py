"""Produce local Java references, never load a mod or publish a remapped JAR."""

from collections import defaultdict
from dataclasses import dataclass
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import tempfile
import zipfile

from .api import ClassLocation
from .classfiles import CLASS_LIMIT, CLASS_NAME, Symbol, class_entries, read_class
from .inputs import InputError, open_input
from .mcp import McpConfig, SRG_ID
from .pack import ModPack, PackMod


VINEFLOWER_ENTRYPOINT = "org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler"
SPECIALSOURCE_ENTRYPOINT = "net.md_5.specialsource.SpecialSource"
WORK_BYTES = 64 * 1024 * 1024


@dataclass(frozen=True)
class DecompileOptions:
    tool: Path
    output: Path
    java: str = "java"
    selected_classes: tuple[str, ...] = ()
    max_classes: int = 250
    timeout: int = 180


def _top_level(owner: str) -> str:
    package, sep, basename = owner.rpartition("/")
    return f"{package}{sep}{basename.split('$', 1)[0]}"


def _verify_tool(path: Path, pack: ModPack, name: str, entrypoint: str) -> Path:
    resolved = path.expanduser().resolve()
    api_inputs = [*pack.context.legacy.paths, *pack.context.target.paths]
    if (resolved.is_relative_to(pack.root) or any(resolved == mod.path for mod in pack.mods.values())
            or any(resolved.is_relative_to(Path(root)) for root in api_inputs)):
        raise InputError(f"Keep the trusted {name} tool JAR outside the modpack and separate from the selected mod and API inputs.")
    if not resolved.is_file() or resolved.suffix.lower() != ".jar":
        raise InputError(f"--{name.lower()} must be a local {name} release JAR.")
    with open_input(resolved) as artifact:
        manifest_path = "META-INF/MANIFEST.MF"
        if manifest_path not in artifact.files:
            raise InputError(f"The selected tool has no JAR manifest; use a trusted {name} release.")
        manifest = artifact.text(manifest_path).replace("\r\n", "\n").split("\n\n", 1)[0].replace("\n ", "")
    match = re.search(r"(?im)^Main-Class:[ \t]*(\S+)[ \t]*$", manifest)
    if not match or match[1] != entrypoint:
        raise InputError(f"The selected tool does not have the expected {name} entry point.")
    return resolved


def _select_locations(pack: ModPack, mod: PackMod, requested: tuple[str, ...]) -> tuple[dict[str, ClassLocation], list[str]]:
    reference_owners = {ref.owner for ref in mod.references if ref.owner not in mod.classes}
    options = {}
    ambiguous = []
    for owner in sorted(reference_owners):
        locations = pack.dependency_locations(mod, owner)
        if len(locations) == 1:
            options[owner] = next(iter(locations))
        elif locations:
            ambiguous.append(owner)
    if requested:
        selected = {}
        for name in requested:
            key = name.replace(".", "/")
            if not CLASS_NAME.fullmatch(key):
                raise InputError(f"Invalid dependency class {name!r}.")
            if key not in options:
                # Allow choosing a readable SRG/MCP name shown in the pack report.
                names = [owner for owner, loc in options.items()
                         if key == loc.owner or any(spelling.owner == key for spelling in
                                                   pack.context.spellings(Symbol(owner), mod.audit.source_version))]
                if len(names) != 1:
                    detail = "ambiguous provider" if key in ambiguous or len(names) > 1 else "no referenced provider"
                    raise InputError(f"Cannot select {name!r}: {detail}. See scan-pack --json.")
                key = names[0]
            selected[key] = options[key]
        options = selected
    if not options:
        raise InputError("No unambiguous dependency classes were found for this mod. Supply the correct legacy API JARs/mappings if needed.")
    return options, ambiguous


def _stage(classes: Path, selected: dict[str, ClassLocation], limit: int, *, kind: str = "Dependency") -> dict[str, str]:
    grouped: dict[Path, set[str]] = defaultdict(set)
    for location in selected.values():
        grouped[location.file].add(location.owner)
    staged = {}
    size = 0
    for path, owners in sorted(grouped.items()):
        top_levels = {_top_level(owner) for owner in owners}
        with open_input(path) as artifact:
            entries = {name: entry for name, entry in class_entries(artifact.files).items()
                       if _top_level(name) in top_levels}
            if not owners.issubset(entries):
                raise InputError(f"{kind} changed during analysis: {path}; rescan before decompiling.")
            if len(staged) + len(entries) > limit:
                raise InputError(f"{kind} selection exceeds {limit} classes (including enclosing/nested classes). Narrow it with --class.")
            for owner, entry in entries.items():
                if owner in staged:
                    raise InputError(f"Duplicate class {owner} would be staged from multiple providers. Narrow the selection.")
                data = artifact.read(entry, CLASS_LIMIT)
                if read_class(data, collect_references=False).owner != owner:
                    raise InputError(f"Dependency member {entry!r} does not declare {owner!r}.")
                size += len(data)
                if size > WORK_BYTES:
                    raise InputError(f"{kind} selection exceeds 64 MiB. Narrow it with --class.")
                member = classes / f"{owner}.class"
                member.parent.mkdir(parents=True, exist_ok=True)
                member.write_bytes(data)
                staged[owner] = str(path)
    return staged


def _env() -> dict[str, str]:
    # Java inherits its runtime/path settings but not the caller's GitHub tokens or JVM injection options.
    keep = {"PATH", "LANG", "LC_ALL", "SYSTEMROOT", "WINDIR", "COMSPEC", "JAVA_HOME", "TEMP", "TMP", "TMPDIR"}
    return {key: value for key, value in os.environ.items() if key.upper() in keep}


def _output(pack: ModPack, options: DecompileOptions, extra_inputs: tuple[Path, ...] = ()) -> Path:
    if not 1 <= options.max_classes <= 2000 or not 1 <= options.timeout <= 1800:
        raise InputError("Use --max-classes 1–2000 and --timeout 1–1800 seconds.")
    output = options.output.expanduser()
    if output.exists() or output.is_symlink():
        raise InputError(f"Output already exists; choose a new directory: {output}")
    output = output.resolve()
    inputs = [pack.root, *map(Path, pack.context.legacy.paths), *map(Path, pack.context.target.paths), *extra_inputs]
    if any(output.is_relative_to(path) for path in inputs):
        raise InputError("Decompiled references must be written outside the modpack and API/mapping inputs.")
    return output


def _java(options: DecompileOptions) -> str:
    java = shutil.which(os.path.expanduser(options.java))
    if not java:
        raise InputError("Java not found; install a JDK for the decompiler and use --java /path/to/java if needed.")
    return str(Path(java).resolve())


def _run_tool(name: str, java: str, tool: Path, args: list[str], work: Path, timeout: int) -> str:
    log = work / f"{name}.log"
    try:
        with log.open("wb") as stream:
            result = subprocess.run([java, "-Xmx1024m", "-jar", str(tool), *args], cwd=work, env=_env(),
                                    stdin=subprocess.DEVNULL, stdout=stream, stderr=subprocess.STDOUT, timeout=timeout)
    except subprocess.TimeoutExpired as exc:
        raise InputError(f"{name} exceeded the {timeout}-second timeout.") from exc
    with log.open("rb") as stream:
        stream.seek(max(0, log.stat().st_size - 64 * 1024))
        diagnostics = stream.read().decode("utf-8", "replace")
    if result.returncode:
        raise InputError(f"{name} exited with status {result.returncode}: {diagnostics[-1200:].strip()!r}")
    return diagnostics


def _vineflower(java: str, tool: Path, classes: Path, owners, work: Path,
                timeout: int) -> tuple[dict[str, Path], str]:
    generated = work / "references"
    generated.mkdir()
    log = _run_tool("Vineflower", java, tool, ["--folder", "--thread-count=2", str(classes), str(generated)], work, timeout)
    expected = {f"{owner}.java" for owner in owners} | {f"{_top_level(owner)}.java" for owner in owners}
    sources = {}
    for relative in sorted(expected):
        path = generated / PurePosixPath(relative)
        if not path.is_symlink() and path.is_file() and path.resolve().is_relative_to(generated):
            sources[relative] = path
    if not sources:
        raise InputError(f"Vineflower produced no expected Java source: {log[-1200:].strip()!r}")
    if sum(path.stat().st_size for path in sources.values()) > WORK_BYTES:
        raise InputError("Decompiler output exceeds 64 MiB. Narrow the class selection.")
    return sources, log


def _missing_sources(owners, sources: dict[str, Path]) -> list[str]:
    return [name for name in sorted(owners) if f"{name}.java" not in sources and f"{_top_level(name)}.java" not in sources]


def _write_references(output: Path, sources: dict[str, Path], manifest: dict, files: dict[str, str]) -> None:
    output.mkdir(parents=True, exist_ok=False)
    try:
        (output / ".gitignore").write_text("*\n!.gitignore\n", encoding="utf-8")
        (output / "decompile-report.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        for name, text in files.items():
            (output / name).write_text(text, encoding="utf-8")
        for rel, path in sources.items():
            dst = output / "sources" / rel
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(path, dst)
    except BaseException:
        shutil.rmtree(output, ignore_errors=True)
        raise


def decompile(pack: ModPack, mod: PackMod, options: DecompileOptions) -> Path:
    output = _output(pack, options)
    tool = _verify_tool(options.tool, pack, "Vineflower", VINEFLOWER_ENTRYPOINT)
    java = _java(options)
    selected, ambiguous = _select_locations(pack, mod, options.selected_classes)
    with tempfile.TemporaryDirectory(prefix="mcmodup-decompile-") as scratch:
        work = Path(scratch)
        classes = work / "class-data"
        staged = _stage(classes, selected, options.max_classes)
        sources, diagnostics = _vineflower(java, tool, classes, staged, work, options.timeout)
        missing = set(_missing_sources(staged, sources))
        manifest = {
            "purpose": "Local reference only; decompiled text is not original source and is not a finished port.",
            "consumer": mod.archive, "consumer_input": pack.origin(mod.path), "source_version": mod.audit.source_version,
            "requested_references": [{"symbol": name, "provider": pack.origin(location.file), "class": location.owner,
                                      "spellings": [s.owner for s in pack.context.spellings(Symbol(name), mod.audit.source_version)]}
                                     for name, location in sorted(selected.items())],
            "member_references": [{"symbol": ref.key, "used_by": mod.references[ref],
                                   "spellings": [s.key for s in pack.context.spellings(ref, mod.audit.source_version)]}
                                  for ref in sorted(mod.references) if ref.owner in selected and ref.name],
            "staged_class_count": len(staged), "source_files": sorted(sources), "ambiguous_references_skipped": ambiguous,
            "references_without_matching_output": [name for name, loc in sorted(selected.items()) if loc.owner in missing],
        }
        _write_references(output, sources, manifest, {"decompiler.log": diagnostics})
    return output


def decompile_mod(pack: ModPack, mod: PackMod, mcp: McpConfig, remapper: Path, options: DecompileOptions,
                  plan: dict, checklist: str) -> Path:
    """Remap a class-only copy, then decompile the selected mod, not its providers."""
    output = _output(pack, options, (mcp.root, mcp.path))
    tool = _verify_tool(options.tool, pack, "Vineflower", VINEFLOWER_ENTRYPOINT)
    remapper = _verify_tool(remapper, pack, "SpecialSource", SPECIALSOURCE_ENTRYPOINT)
    if any(path.is_relative_to(mcp.path) for path in (tool, remapper)):
        raise InputError("Keep executable tools separate from the MCP configuration.")
    java = _java(options)
    owners = [name.replace(".", "/") for name in options.selected_classes] or sorted(mod.classes)
    for name in owners:
        if name not in mod.classes:
            raise InputError(f"Class {name!r} is not indexed in selected mod {mod.archive!r}.")
    if not owners:
        raise InputError(f"Selected mod {mod.archive!r} has no indexable bytecode.")
    selected = {name: ClassLocation(mod.path, mod.classes[name], name) for name in owners}

    with tempfile.TemporaryDirectory(prefix="mcmodup-mcp-") as scratch:
        work = Path(scratch)
        raw, mapped = work / "raw", work / "mapped"
        staged = _stage(raw, selected, options.max_classes, kind="Mod")
        classes = {name: read_class((raw / f"{name}.class").read_bytes()) for name in staged}
        # Reuse headers already read by the scanner. Include only unambiguous
        # same-release peers, without passing any of their bytecode to the tools.
        hierarchy = {name: entries[0][1].parents for name, entries in pack.context.legacy.classes.items() if len(entries) == 1}
        for peer in pack.mods.values():
            if peer is not mod and peer.audit.source_version == mcp.version:
                for name, parents in peer.parents.items():
                    if len(pack.owners.get(name, ())) == 1:
                        hierarchy.setdefault(name, parents)
        hierarchy.update(mod.parents)
        mapping = work / "mcp.srg"
        mcp.remap_file(mapping, classes, hierarchy)
        mapped_names = {name: mcp.class_name(name) for name in staged}
        if len(set(mapped_names.values())) != len(staged):
            raise InputError("MCP class names collide inside the selected mod; review the selected mapping and classes.")
        original, renamed = work / "classes.jar", work / "renamed.jar"
        with zipfile.ZipFile(original, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for owner in sorted(staged):
                archive.write(raw / f"{owner}.class", f"{owner}.class")
        remap_log = _run_tool("SpecialSource", java, remapper,
                              ["--in-jar", str(original), "--out-jar", str(renamed), "--srg-in", str(mapping)],
                              work, options.timeout)
        expected = set(mapped_names.values())
        remaining = set()
        total = 0
        old_refs = {ref for info in classes.values() for ref in info.references}
        mapped_refs = set()
        with open_input(renamed) as artifact:
            entries = class_entries(artifact.files)
            if set(entries) != expected:
                raise InputError("SpecialSource did not produce exactly the expected class names; check the tool and MCP config.")
            for owner, entry in entries.items():
                data = artifact.read(entry, CLASS_LIMIT)
                info = read_class(data)
                if info.owner != owner:
                    raise InputError(f"Remapped class {entry!r} declares unexpected owner {info.owner!r}.")
                total += len(data)
                if total > WORK_BYTES:
                    raise InputError("Remapped class data exceeds 64 MiB. Narrow it with --class.")
                member = mapped / entry
                member.parent.mkdir(parents=True, exist_ok=True)
                member.write_bytes(data)
                mapped_refs.update(info.references)
                remaining.update(Symbol(info.owner, name, desc) for name, desc in info.members if SRG_ID.fullmatch(name))
            remaining.update(ref for ref in mapped_refs if SRG_ID.fullmatch(ref.name))
        sources, diagnostics = _vineflower(java, tool, mapped, expected, work, options.timeout)
        manifest = {
            "purpose": "Local same-version MCP-named references only; not original source, not a port.",
            "mod": plan["input"], "mcp": mcp.evidence(),
            "staged_class_count": len(staged), "renamed_classes": {raw: name for raw, name in mapped_names.items() if raw != name},
            "input_reference_symbols_removed_or_renamed": len(old_refs - mapped_refs),
            "remaining_srg_symbols": sorted(symbol.key for symbol in remaining),
            "source_files": sorted(sources), "classes_without_matching_output": _missing_sources(expected, sources),
        }
        _write_references(output, sources, manifest, {"decompiler.log": diagnostics, "remapper.log": remap_log,
                          "port-plan.json": json.dumps(plan, indent=2) + "\n", "PORTING.md": checklist})
    return output
