"""Decompile only selected, observed dependency classes into local reference files."""

from collections import defaultdict
from dataclasses import dataclass
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import tempfile

from .api import ClassLocation
from .classfiles import CLASS_LIMIT, CLASS_NAME, Symbol, class_entries, read_class
from .inputs import InputError, open_input
from .pack import ModPack, PackMod


VINEFLOWER_ENTRYPOINT = "org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler"
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


def _verify_tool(path: Path, pack: ModPack) -> Path:
    resolved = path.expanduser().resolve()
    api_inputs = [*pack.context.legacy.paths, *pack.context.target.paths]
    if resolved.is_relative_to(pack.root) or str(resolved) in api_inputs:
        raise InputError("Keep the trusted Vineflower tool JAR outside the modpack and API inputs.")
    if not resolved.is_file() or resolved.suffix.lower() != ".jar":
        raise InputError("--vineflower must be a local Vineflower release JAR.")
    with open_input(resolved) as artifact:
        manifest_path = "META-INF/MANIFEST.MF"
        if manifest_path not in artifact.files:
            raise InputError("The selected decompiler has no JAR manifest; use a trusted Vineflower release.")
        manifest = artifact.text(manifest_path).replace("\r\n", "\n").split("\n\n", 1)[0].replace("\n ", "")
    match = re.search(r"(?im)^Main-Class:[ \t]*(\S+)[ \t]*$", manifest)
    if not match or match[1] != VINEFLOWER_ENTRYPOINT:
        raise InputError("The selected decompiler does not have the expected Vineflower entry point.")
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


def _stage(classes: Path, selected: dict[str, ClassLocation], limit: int) -> dict[str, str]:
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
                raise InputError(f"Dependency changed during analysis: {path}; rescan before decompiling.")
            if len(staged) + len(entries) > limit:
                raise InputError(f"Dependency selection exceeds {limit} classes (including enclosing/nested classes). Narrow it with --class.")
            for owner, entry in entries.items():
                if owner in staged:
                    raise InputError(f"Duplicate class {owner} would be staged from multiple providers. Narrow the selection.")
                data = artifact.read(entry, CLASS_LIMIT)
                if read_class(data, collect_references=False).owner != owner:
                    raise InputError(f"Dependency member {entry!r} does not declare {owner!r}.")
                size += len(data)
                if size > WORK_BYTES:
                    raise InputError("Dependency selection exceeds 64 MiB. Narrow it with --class.")
                member = classes / f"{owner}.class"
                member.parent.mkdir(parents=True, exist_ok=True)
                member.write_bytes(data)
                staged[owner] = str(path)
    return staged


def _env() -> dict[str, str]:
    # Java inherits its runtime/path settings but not the caller's GitHub tokens or JVM injection options.
    keep = {"PATH", "LANG", "LC_ALL", "SYSTEMROOT", "WINDIR", "COMSPEC", "JAVA_HOME", "TEMP", "TMP", "TMPDIR"}
    return {key: value for key, value in os.environ.items() if key.upper() in keep}


def decompile(pack: ModPack, mod: PackMod, options: DecompileOptions) -> Path:
    if not 1 <= options.max_classes <= 2000 or not 1 <= options.timeout <= 1800:
        raise InputError("Use --max-classes 1–2000 and --timeout 1–1800 seconds.")
    output = options.output.expanduser()
    if output.exists() or output.is_symlink():
        raise InputError(f"Output already exists; choose a new directory: {output}")
    output = output.resolve()
    if output.is_relative_to(pack.root) or any(output.is_relative_to(Path(path)) for path in pack.context.legacy.paths):
        raise InputError("Decompiled references must be written outside the modpack and API inputs.")
    tool = _verify_tool(options.tool, pack)
    java = shutil.which(os.path.expanduser(options.java))
    if not java:
        raise InputError("Java not found; install a JDK for Vineflower and use --java /path/to/java if needed.")
    java = str(Path(java).resolve())
    selected, ambiguous = _select_locations(pack, mod, options.selected_classes)
    with tempfile.TemporaryDirectory(prefix="mcmodup-decompile-") as scratch:
        work = Path(scratch)
        classes = work / "class-data"
        generated = work / "references"
        generated.mkdir()
        staged = _stage(classes, selected, options.max_classes)
        log = work / "decompiler.log"
        try:
            with log.open("wb") as stream:
                result = subprocess.run([java, "-Xmx1024m", "-jar", str(tool), "--folder", "--thread-count=2",
                                         str(classes), str(generated)], cwd=work, env=_env(), stdin=subprocess.DEVNULL,
                                        stdout=stream, stderr=subprocess.STDOUT, timeout=options.timeout)
        except subprocess.TimeoutExpired as exc:
            raise InputError(f"Vineflower exceeded the {options.timeout}-second timeout.") from exc
        with log.open("rb") as stream:
            stream.seek(max(0, log.stat().st_size - 64 * 1024))
            diagnostics = stream.read().decode("utf-8", "replace")
        if result.returncode:
            raise InputError(f"Vineflower exited with status {result.returncode}: {diagnostics[-1200:].strip()!r}")
        expected = {f"{owner}.java" for owner in staged} | {f"{_top_level(owner)}.java" for owner in staged}
        sources = {}
        for relative in sorted(expected):
            path = generated / PurePosixPath(relative)
            if not path.is_symlink() and path.is_file() and path.resolve().is_relative_to(generated):
                sources[relative] = path
        if not sources:
            raise InputError(f"Vineflower produced no expected Java source: {diagnostics[-1200:].strip()!r}")
        if sum(path.stat().st_size for path in sources.values()) > WORK_BYTES:
            raise InputError("Decompiler output exceeds 64 MiB. Narrow the class selection.")
        manifest = {
            "purpose": "Local reference only; decompiled text is not original source and is not a finished port.",
            "consumer": mod.archive, "source_version": mod.audit.source_version,
            "requested_references": [{"symbol": name, "provider": str(location.file), "class": location.owner,
                                      "spellings": [s.owner for s in pack.context.spellings(Symbol(name), mod.audit.source_version)]}
                                     for name, location in sorted(selected.items())],
            "member_references": [{"symbol": ref.key, "used_by": mod.references[ref],
                                   "spellings": [s.key for s in pack.context.spellings(ref, mod.audit.source_version)]}
                                  for ref in sorted(mod.references) if ref.owner in selected and ref.name],
            "staged_class_count": len(staged), "source_files": sorted(sources), "ambiguous_references_skipped": ambiguous,
            "references_without_matching_output": [name for name, loc in sorted(selected.items())
                                                    if f"{loc.owner}.java" not in sources and f"{_top_level(loc.owner)}.java" not in sources],
        }
        output.mkdir(parents=True, exist_ok=False)
        try:
            (output / ".gitignore").write_text("*\n!.gitignore\n", encoding="utf-8")
            (output / "decompile-report.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
            (output / "decompiler.log").write_text(diagnostics, encoding="utf-8")
            for rel, path in sources.items():
                dst = output / "sources" / rel
                dst.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(path, dst)
        except BaseException:
            shutil.rmtree(output, ignore_errors=True)
            raise
    return output
