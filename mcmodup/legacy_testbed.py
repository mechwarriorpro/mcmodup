"""Build the exact, private-use NeoForge testbed as a single flat multi-mod JAR."""

from hashlib import sha256
import json
from pathlib import Path
import shutil
import tempfile
import tomllib
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

from . import MINECRAFT_VERSION, NEOFORGE_VERSION, atomic_thermometer, geostrata_rocks, icbm_plate, minechem_pair
from .classfiles import CLASS_LIMIT, read_class
from .inputs import InputError, MOD_ARCHIVE_LIMIT, open_input
from .method_ports import PROVENANCE
from .port import _toml
from .source_ports import BuildOptions, build, digest, load_ports, require_jdk


JAR_NAME = f"mcmodup-legacy-testbed-{MINECRAFT_VERSION}.jar"
REPORT_NAME = "testbed-report.json"
VOLTZ_SHA256 = "2eda607746a22edbf45612243adce37a876251e36d46c692a48344fe98eec84e"
VOLTZ_MEMBERS = {
    "mods/atomicscience.jar": atomic_thermometer.LEGACY_SHA256,
    "mods/icbm-1.4.1.344.jar": icbm_plate.LEGACY_SHA256,
    "mods/calclavia-core-development-1.2.0.6-main.jar": "0372d373993769264da00cef69c3814f89295856d5f14dd570d6a7aaac6c5585",
}
COMPONENTS = {
    **minechem_pair.VERSIONS,
    atomic_thermometer.MOD_ID: atomic_thermometer.VERSION,
    icbm_plate.MOD_ID: icbm_plate.VERSION,
    geostrata_rocks.MOD_ID: geostrata_rocks.VERSION,
}
METADATA = "META-INF/neoforge.mods.toml"
AUDIT = "META-INF/mcmodup/testbed.json"
ARTIFACT_MARKERS = (PROVENANCE, "META-INF/MCModUp-NOTICE.txt")

# Owners are read from the original archives. A bytecode inventory is not a promise of feature parity.
TARGETS = {
    "minechem": ("minechem/tileentity/decomposer/DecomposerTileEntity", "minechem/tileentity/synthesis/SynthesisTileEntity"),
    "icbm": ("icbm/explosion/machines/TileLauncherBase", "icbm/explosion/machines/TileLauncherFrame",
             "icbm/explosion/machines/TileLauncherScreen", "icbm/explosion/missile/missile/EntityMissile",
             "icbm/explosion/missile/modular/TileMissileAssembler", "icbm/sentry/turret/auto/TurretAntiAir"),
    "atomic": ("atomicscience/particle/accelerator/TileAccelerator", "atomicscience/particle/accelerator/EntityParticle",
               "atomicscience/schematic/SchematicAccelerator"),
    "geostrata": ("Reika/GeoStrata/Blocks/BlockCaveCrystal", "Reika/GeoStrata/Blocks/BlockCrystalLamp",
                  "Reika/GeoStrata/Blocks/BlockSuperCrystal", "Reika/GeoStrata/Items/ItemCrystalShard"),
    "autobuilder": ("calclavia/components/creative/BlockCreativeBuilder", "calclavia/lib/schematic/Schematic"),
}


def _json(value: object) -> bytes:
    return (json.dumps(value, indent=2, sort_keys=True) + "\n").encode()


def _check(path: Path, expected: str) -> Path:
    path = path.expanduser().resolve()
    if not path.is_file() or path.stat().st_size > MOD_ARCHIVE_LIMIT or digest(path) != expected:
        raise InputError(f"Wrong or missing exact original: {path.name!r} (expected SHA-256 {expected}).")
    return path


def scan(path: Path, owners: tuple[str, ...]) -> dict:
    with open_input(path) as original:
        if original.warnings:
            raise InputError(f"Unsafe or duplicate members in {path.name!r}: {original.warnings}")
        classes = {}
        for owner in owners:
            raw = original.read(owner + ".class", CLASS_LIMIT)
            info = read_class(raw)
            if info.owner != owner:
                raise InputError(f"Unexpected legacy class name {info.owner!r} for {owner!r}")
            classes[owner] = {"sha256": sha256(raw).hexdigest(), "parents": list(info.parents),
                              "members": [name + ("" if desc.startswith("(") else ":") + desc for name, desc in sorted(info.members)],
                              "legacy_references": sorted({ref.owner for ref in info.references if ref.owner.startswith((
                                  "net/minecraft/", "net/minecraftforge/", "cpw/mods/", "calclavia/", "universalelectricity/", "Reika/DragonAPI/"))})}
    return {"archive": path.name, "sha256": digest(path), "classes": classes}


def _metadata(components: list[tuple[str, dict]]) -> bytes:
    lines = ['modLoader="javafml"', 'loaderVersion="[1,)"',
             'license="Original component terms apply. Private-use testbed; do not redistribute."']
    for mod_id, metadata in components:
        lines += ["", "[[mods]]"]
        for key, value in metadata["mods"][0].items():
            lines.append(f"{key}={_toml(value)}")
        for row in metadata["dependencies"][mod_id]:
            lines += ["", f"[[dependencies.{mod_id}]]", *(f"{key}={_toml(value)}" for key, value in row.items())]
    return ("\n".join(lines) + "\n").encode()


def _validate_metadata(archive, mod_id: str, version: str) -> dict:
    try:
        meta = tomllib.loads(archive.read(METADATA, 65536).decode())
        [mod] = meta["mods"]
        deps = meta["dependencies"]
        targets = {row["modId"]: row["versionRange"] for row in deps[mod_id]}
        if (set(meta) != {"modLoader", "loaderVersion", "license", "mods", "dependencies"}
                or meta["modLoader"] != "javafml" or meta["loaderVersion"] != "[1,)"
                or set(deps) != {mod_id} or mod["modId"] != mod_id or mod["version"] != version
                or set(mod) - {"modId", "version", "displayName", "description", "authors"}
                or not all(isinstance(v, str) for v in mod.values()) or len(deps[mod_id]) != 2
                or targets != {"minecraft": f"[{MINECRAFT_VERSION}]", "neoforge": f"[{NEOFORGE_VERSION}]"}
                or any(row != {"modId": row["modId"], "type": "required", "versionRange": row["versionRange"],
                               "ordering": "NONE", "side": "BOTH"} for row in deps[mod_id])):
            raise ValueError("Unexpected mod metadata, loader directive or dependency")
    except (KeyError, TypeError, ValueError) as error:
        raise InputError(f"Cannot combine {mod_id!r}: {error}") from error
    return meta


def _additive_tag(data: bytes) -> list[str]:
    try:
        tag = json.loads(data)
        if set(tag) != {"replace", "values"} or tag["replace"] is not False or not isinstance(tag["values"], list):
            raise ValueError("only additive tags can be combined")
        if any(not isinstance(entry, str) for entry in tag["values"]):
            raise ValueError("only inline tag IDs are supported here")
        return tag["values"]
    except (TypeError, ValueError) as error:
        raise InputError(f"Cannot combine duplicate tag: {error}") from error


def _combine(partials: dict[str, Path], audit: dict, output: Path) -> None:
    """Only called with components we just built. Refuse unknown layout and duplicate classes."""
    if set(partials) != set(COMPONENTS):
        raise InputError("The testbed requires all five native components")
    files, metadata = {}, []
    for mod_id, path in sorted(partials.items()):
        with open_input(path) as archive:
            if archive.warnings:
                raise InputError(f"Unsafe or duplicate members in production component {mod_id!r}")
            meta = _validate_metadata(archive, mod_id, COMPONENTS[mod_id])
            metadata.append((mod_id, meta))
            files[f"META-INF/mcmodup/{mod_id}/metadata.toml"] = archive.read(METADATA)
            for name in sorted(archive.files):
                if name == METADATA:
                    continue
                if name == "META-INF/MANIFEST.MF":
                    if archive.read(name, 65536).strip() != b"Manifest-Version: 1.0":
                        raise InputError(f"Unexpected JAR manifest attributes in {mod_id!r}")
                    continue
                relocated = name
                if name in ARTIFACT_MARKERS:
                    relocated = f"META-INF/mcmodup/{mod_id}/{name.rsplit('/', 1)[1]}"
                elif (name.endswith(".jar") or not (name.startswith((f"{mod_id}/", "mcmodup/", "mcmodup_kernel/", "data/", "assets/"))
                                                      or name in {"pack.mcmeta"})):
                    raise InputError(f"Unexpected resource or embedded library in {mod_id!r}: {name!r}")
                data = archive.read(name, CLASS_LIMIT)
                if relocated in files:
                    if name.startswith(("data/minecraft/tags/", "data/c/tags/")) and name.endswith(".json"):
                        data = _json({"replace": False, "values": sorted(set(_additive_tag(files[name]) + _additive_tag(data)))})
                    elif name != "pack.mcmeta" or data != files[name]:
                        raise InputError(f"Testbed resource collision: {name!r}")
                files[relocated] = data
    files[METADATA] = _metadata(metadata)
    files[AUDIT] = _json(audit)
    files["META-INF/MANIFEST.MF"] = b"Manifest-Version: 1.0\r\n\r\n"
    with ZipFile(output, "x", compression=ZIP_DEFLATED) as archive:
        for name, content in sorted(files.items()):
            entry = ZipInfo(name)
            entry.create_system = 3
            entry.external_attr = 0o100644 << 16
            archive.writestr(entry, content, compress_type=ZIP_DEFLATED)


def build_testbed(final: Path, release394: Path, voltz: Path, geo: Path, output: Path, *,
                  allow_preview: bool = False, timeout: int = 600) -> Path:
    if not allow_preview:
        raise InputError("The one-JAR testbed is incomplete and private-use. Pass --allow-preview-ports to build it.")
    if not 1 <= timeout <= 1800:
        raise InputError("The build timeout must be 1–1800 seconds per component.")
    output = output.expanduser()
    if output.exists() or output.is_symlink():
        raise InputError("Choose a new output directory outside your installed instances.")
    final = _check(final, minechem_pair.FINAL_LEGACY_SHA256)
    release394 = _check(release394, minechem_pair.RELEASE_394_SHA256)
    geo = _check(geo, geostrata_rocks.LEGACY_SHA256)
    voltz = _check(voltz, VOLTZ_SHA256)
    java = require_jdk()
    with tempfile.TemporaryDirectory(prefix="mcmodup-testbed-") as temp:
        root = Path(temp)
        with ZipFile(voltz) as pack:
            atomic, icbm, core = [root / Path(member).name for member in VOLTZ_MEMBERS]
            for member, dest in zip(VOLTZ_MEMBERS, (atomic, icbm, core)):
                if pack.getinfo(member).file_size > MOD_ARCHIVE_LIMIT:
                    raise InputError(f"Voltz member {member!r} exceeds the archive size limit")
                dest.write_bytes(pack.read(member))
                _check(dest, VOLTZ_MEMBERS[member])
        originals = (final, release394, atomic, icbm, geo)
        before = {path: digest(path) for path in (*originals, core, voltz)}
        evidence = {"voltz_sha256": before[voltz], "minecraft": MINECRAFT_VERSION, "neoforge": NEOFORGE_VERSION,
                    "preview": True, "parity_verified": False, "staged_gameplay_verified": False,
                    "private_use_only": True, "scan": {key: scan(path, TARGETS[group]) for key, path, group in (
                        ("minechem_final", final, "minechem"), ("minechem_394", release394, "minechem"),
                        ("atomic_science", atomic, "atomic"), ("icbm", icbm, "icbm"),
                        ("geostrata", geo, "geostrata"), ("autobuilder", core, "autobuilder"))}, "components": {}}
        pair = minechem_pair.prepare(final, release394, root / "minechem")
        manifests = (atomic_thermometer.prepare(root / "atomic"), icbm_plate.prepare(root / "icbm"),
                     geostrata_rocks.prepare(root / "geo"))
        specs = load_ports(BuildOptions(pair, allow_preview=True, timeout=timeout, additional_manifests=manifests))
        parts = {}
        for index, original in enumerate(originals):
            spec = specs[before[original]]
            (mod_id,) = spec.ids
            print(f"Building and testing {mod_id} …", flush=True)
            parts[mod_id], result = build(spec, original, root / f"build-{index}", [], NEOFORGE_VERSION, java, timeout)
            evidence["components"][mod_id] = {**result, "jar_sha256": digest(parts[mod_id])}
        if any(digest(path) != expected for path, expected in before.items()):
            raise InputError("A source archive changed while building the testbed")
        artifact = root / JAR_NAME
        _combine(parts, evidence, artifact)
        output.mkdir(parents=True, exist_ok=False)
        try:
            shutil.copyfile(artifact, output / JAR_NAME)
            (output / REPORT_NAME).write_bytes(_json({**evidence, "jar": JAR_NAME, "jar_sha256": digest(artifact)}))
        except BaseException:
            shutil.rmtree(output)
            raise
    return output.resolve() / JAR_NAME
