"""Read local Minecraft/loader evidence and locate the files for a fresh target instance."""

from dataclasses import dataclass, field, replace
import hashlib
import json
import os
from pathlib import Path
import re
import shlex

from . import MINECRAFT_VERSION, SOURCE_VERSIONS
from .audit import FILENAME_VERSION
from .inputs import InputError, open_input

ARCHIVE_EXTENSIONS = {".jar", ".zip"}
NEOFORGE_JAR = re.compile(r"neoforge-(21\.1\.\d+)(?:-universal)?\.jar", re.I)
MINECRAFT_RELEASE = re.compile(r"1\.\d+(?:\.\d+)?")
NEO_CLASS = "net/neoforged/neoforge/common/NeoForge.class"
FORGE_CLASSES = {"net/minecraftforge/common/MinecraftForge.class", "cpw/mods/fml/common/Loader.class"}
LEGACY_CLIENTS = {  # Official vanilla client SHA-1s (pre-version.json releases).
    "465378c9dc2f779ae1d6e8046ebc46fb53a57968": "1.5.2",
    "1703704407101cf72bd88e68579e3696ce733ecd": "1.6.4",
    "e80d9b3bf5085002218d4be59e668bac718abbc6": "1.7.10",
}
NEO_LIB_DIR = "libraries/net/neoforged/neoforge"
SERVER_LIB_DIR = "libraries/net/minecraft/server"


def local_files(root: Path, directory: str) -> list[Path]:
    base = root / directory
    if any(path.is_symlink() for path in (*base.parents[:len(Path(directory).parts) - 1], base)):
        raise InputError(f"Use a real {directory}/ folder, not a symbolic link: {root}")
    if not base.is_dir():
        return []
    result = []

    def onerror(exc: OSError) -> None:
        raise exc

    for current, dirs, files in os.walk(base, onerror=onerror):
        parent = Path(current)
        dirs[:] = sorted(name for name in dirs if not name.startswith("."))
        for name in (*dirs, *files):
            if (parent / name).is_symlink():
                raise InputError(f"Symbolic links are not scanned: {parent / name}")
        result.extend(parent / name for name in sorted(files) if (parent / name).is_file())
    return sorted(result)


def file_digest(path: Path, algorithm: str = "sha256") -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, algorithm).hexdigest()


@dataclass(frozen=True)
class PlatformFile:
    path: str
    role: str
    sha256: str
    minecraft: tuple[tuple[str, str], ...]  # (version, provenance)
    loader_version: str | None = None

    def report(self) -> dict:
        return {"file": self.path, "role": self.role, "sha256": self.sha256, "loader_version": self.loader_version,
                "minecraft_evidence": [{"version": v, "from": why} for v, why in self.minecraft]}


@dataclass(frozen=True)
class Platform:
    files: tuple[PlatformFile, ...]
    warnings: tuple[str, ...] = ()

    @property
    def minecraft(self) -> list[tuple[str, str]]:
        return sorted({(v, f.path) for f in self.files for v, _ in f.minecraft})

    @property
    def loaders(self) -> list[tuple[str, str]]:
        return [(f.loader_version, f.path) for f in self.files if f.role == "neoforge"]

    @property
    def neoforge(self) -> str | None:
        versions = {version for version, _ in self.loaders}
        return next(iter(versions)) if len(versions) == 1 else None

    @property
    def legacy(self) -> str | None:
        versions = {version for version, _ in self.minecraft}
        return next(iter(versions)) if len(versions) == 1 and versions <= set(SOURCE_VERSIONS) else None

    def legacy_apis(self, root: Path, release: str) -> list[Path]:
        if self.legacy != release:
            return []
        return [root / f.path for f in self.files if f.role in {"minecraft", "forge"} and f.path.startswith("bin/")]

    def report(self) -> dict:
        return {"minecraft": [{"version": v, "file": f} for v, f in self.minecraft],
                "neoforge": self.neoforge, "neoforge_files": [f for _, f in self.loaders],
                "forge_files": [f.path for f in self.files if f.role == "forge"],
                "archives": [f.report() for f in self.files], "warnings": list(self.warnings)}


def _version_evidence(jar, role: str) -> set[tuple[str, str]]:
    evidence = set()
    if "version.json" in jar.files:
        data = json.loads(jar.text("version.json"))
        version = (data.get("id") or data.get("name")) if isinstance(data, dict) else None
        if isinstance(version, str) and MINECRAFT_RELEASE.fullmatch(version):
            evidence.add((version, "version.json"))
    if role == "minecraft" and "META-INF/versions.list" in jar.files:
        for line in jar.text("META-INF/versions.list").splitlines():
            fields = line.split("\t")
            if len(fields) == 3 and MINECRAFT_RELEASE.fullmatch(fields[1]) and f"META-INF/versions/{fields[2]}" in jar.files:
                evidence.add((fields[1], "META-INF/versions.list"))
    if role == "forge":
        for name, key in (("fmlversion.properties", "fmlbuild.mcversion"),
                          ("forgeversion.properties", "forge.mcversion")):
            if name not in jar.files:
                continue
            for line in jar.text(name).splitlines():
                match = re.fullmatch(rf"\s*{re.escape(key)}\s*[:=]\s*({MINECRAFT_RELEASE.pattern})\s*", line)
                if match:
                    evidence.add((match[1], name))
    return evidence


def discover(root: Path, *, include_server: bool = False, bins: list[Path] | None = None) -> Platform:
    candidates = local_files(root, "bin") if bins is None else list(bins)
    if include_server:
        candidates += local_files(root, NEO_LIB_DIR) + local_files(root, SERVER_LIB_DIR)
    files, warnings = [], []
    for path in sorted(set(candidates)):
        if path.suffix.lower() not in ARCHIVE_EXTENSIONS:
            continue
        name = path.name.lower()
        neo = NEOFORGE_JAR.fullmatch(name)
        minecraft = (name.startswith(("minecraft", "client-", "server-")) or MINECRAFT_RELEASE.fullmatch(path.stem))
        forge = name == "modpack.jar" or re.match(r"(?:minecraftforge|forge|fml)(?:[-_.]|$)", name)
        if not (neo or minecraft or forge):
            continue
        label = path.relative_to(root).as_posix()
        try:
            with open_input(path) as jar:
                if jar.warnings:
                    raise InputError("unsafe or duplicate members")
                if neo and NEO_CLASS in jar.files:
                    files.append(PlatformFile(label, "neoforge", file_digest(path), (), neo[1]))
                    continue
                if forge and FORGE_CLASSES.intersection(jar.files):
                    role = "forge"
                elif minecraft:
                    role = "minecraft"
                else:
                    continue
                evidence = _version_evidence(jar, role)
                if role == "forge" and not evidence:
                    # These filenames are non-authoritative; the loader marker proves this is a runtime,
                    # but a hint alone cannot select an API profile.
                    if hints := FILENAME_VERSION.findall(name):
                        warnings.append(f"{label}: filename suggests Minecraft {', '.join(hints)}; no exact release metadata.")
                if role == "minecraft" and not evidence and name == "minecraft.jar":
                    if release := LEGACY_CLIENTS.get(file_digest(path, "sha1")):
                        evidence.add((release, "vanilla SHA-1"))
                # A launcher minecraft.jar without version metadata can be paired to the Forge
                # modpack.jar's exact profile; arbitrary client/server-named caches are not evidence.
                if role == "forge" or evidence or label == "bin/minecraft.jar":
                    files.append(PlatformFile(label, role, file_digest(path), tuple(sorted(evidence))))
        except (InputError, OSError, ValueError, RecursionError) as exc:
            warnings.append(f"{label}: could not read runtime evidence: {exc}")
    return Platform(tuple(files), tuple(warnings))


@dataclass(frozen=True)
class InstanceRuntime:
    layout: str
    files: dict[str, str] = field(default_factory=dict)  # instance-relative path -> SHA-256
    launch: dict[str, str] = field(default_factory=dict)

    def report(self) -> dict:
        return {"layout": self.layout, "files": [{"file": path, "sha256": sha} for path, sha in sorted(self.files.items())],
                "launch": self.launch, "boot_verified": False}


# Stock server installer arguments hold relative module/class paths. Restrict copying to
# those JARs and the matching argfiles, never run scripts or copy arbitrary libraries caches.
_LIB_JAR = re.compile(r"libraries/(?:[\w.+-]+/)+[\w.+-]+\.jar", re.ASCII)
_BOOTSTRAP = "cpw.mods.bootstraplauncher.BootstrapLauncher"


def _server_args(root: Path, rel: str, version: str, sep: str) -> set[str]:
    path = root / rel
    if path.stat().st_size > 128 * 1024:
        raise InputError(f"Server installer arguments are too large: {rel}")
    text = path.read_text(encoding="utf-8")
    try:
        args = shlex.split(text, comments=True)
    except ValueError as exc:
        raise InputError(f"Unreadable NeoForge server arguments: {rel}") from exc

    def value(option: str) -> str:
        if args.count(option) == 1 and args.index(option) + 1 < len(args):
            return args[args.index(option) + 1]
        return ""

    if (_BOOTSTRAP not in args or value("--launchTarget") not in {"forgeserver", "neoforgeserver"} or
            value("--fml.mcVersion") != MINECRAFT_VERSION or value("--fml.neoForgeVersion") != version or
            "-DlibraryDirectory=libraries" not in args or any(arg.startswith(("@", "-javaagent:", "-agentlib:", "-agentpath:")) for arg in args)):
        raise InputError(f"Use stock NeoForge {version} server installer arguments: {rel}")
    class_path = next((arg.partition("=")[2] for arg in args if arg.startswith("-DlegacyClassPath=")), "")
    module_path = value("-p") or value("--module-path")
    paths = {part for group in (class_path, module_path) if group for part in group.split(sep)}
    if not paths or any(not _LIB_JAR.fullmatch(p) or any(part in {".", ".."} for part in p.split("/")) for p in paths):
        raise InputError(f"Use relative installer library paths in {rel}")
    return paths


def instance_runtime(root: Path, platform: Platform) -> InstanceRuntime:
    version = platform.neoforge
    base = f"{NEO_LIB_DIR}/{version}"
    known = {row.path: row for row in platform.files}
    if any(f.role == "neoforge" and f.path.startswith(base + "/") for f in platform.files):
        paths = set()
        launch = {}
        for os_name, separator in (("unix", ":"), ("win", ";")):
            rel = f"{base}/{os_name}_args.txt"
            if not (root / rel).is_file():
                continue
            paths.update(_server_args(root, rel, version, separator))
            paths.add(rel)
            launch[os_name] = f"java -Xmx4G @{rel} nogui"
        if not launch:
            raise InputError(f"Target server has no installed {base}/unix_args.txt or win_args.txt")
        if not any(p in known and known[p].role == "neoforge" for p in paths):
            raise InputError("Server installer arguments do not reference the selected NeoForge runtime")
        if not any((MINECRAFT_VERSION, p) in platform.minecraft for p in paths):
            raise InputError(f"Server installer arguments do not reference an identifiable Minecraft {MINECRAFT_VERSION} JAR")
        files = {}
        for rel in sorted(paths):
            path = root / rel
            if not path.is_file() or path.resolve() != path:
                raise InputError(f"Missing or linked server installer file: {rel}")
            files[rel] = known[rel].sha256 if rel in known else file_digest(path)
        return InstanceRuntime("neoforge-server", files, launch)

    files = {f.path: f.sha256 for f in platform.files if f.path.startswith("bin/") and
             (f.role == "neoforge" or (f.role == "minecraft" and (MINECRAFT_VERSION, f.path) in platform.minecraft))}
    return InstanceRuntime("launcher-game-directory", files)
