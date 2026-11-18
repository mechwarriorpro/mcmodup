"""Build explicit, fingerprinted native source ports, not unknown modpack scripts."""

from collections import Counter
from dataclasses import dataclass, field
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import stat
import subprocess
from urllib.error import URLError
from urllib.request import HTTPRedirectHandler, build_opener
import xml.etree.ElementTree as ET
import zipfile
import zlib

from .assets import LOCALE_FILENAME, _read_lang
from .classfiles import CLASS_LIMIT
from .inputs import InputError, MOD_ARCHIVE_LIMIT, ModInput, open_input

MOD_ID = re.compile(r"[a-z][a-z0-9_]{1,63}")
RESERVED = {"minecraft", "neoforge", "forge", "legacyconnector"}
SHA256 = re.compile(r"[a-f0-9]{64}")
VERSION = re.compile(r"[0-9A-Za-z][0-9A-Za-z._+-]{0,127}")
SOURCE_URL = re.compile(r"https://codeload\.github\.com/[\w.-]+/[\w.-]+/zip/[a-f0-9]{40}", re.ASCII)
TASK = re.compile(r":?(?:[A-Za-z0-9_-]+:)*[A-Za-z0-9_][A-Za-z0-9_-]*", re.ASCII)
MAX_PROJECT_BYTES = 512 * 1024 * 1024
MAX_PROJECT_FILES = 30_000


def digest(path: Path, algorithm: str = "sha256") -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, algorithm).hexdigest()


def safe_component(name: str) -> bool:
    return not (name in {"", ".", ".."} or name.endswith((" ", ".")) or
                re.fullmatch(r"(?i)CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]", name.partition(".")[0]) or
                any(c in name for c in '<>:"/\\|?*') or
                any(ord(c) < 32 or ord(c) == 127 or 0xD800 <= ord(c) <= 0xDFFF for c in name))


def _relative(value: object) -> str:
    if not isinstance(value, str) or len(value) > 4096 or any(not safe_component(part) for part in value.split("/")):
        raise InputError(f"Expected a safe relative path: {value!r}")
    return value


def _object(value: object) -> dict:
    if not isinstance(value, dict):
        raise InputError("Expected a JSON object")
    return value


def _unique(pairs: list[tuple[str, object]]) -> dict:
    result = dict(pairs)
    if len(result) != len(pairs):
        raise InputError("Duplicate JSON key in source port manifest")
    return result


@dataclass(frozen=True)
class Dependency:
    mod_id: str
    version: str
    kind: str = "required"


@dataclass(frozen=True)
class BuildOptions:
    manifest: Path
    allow_preview: bool = False
    timeout: int = 1200


@dataclass(frozen=True)
class SourcePort:
    legacy_sha256: str
    archive: str | Path
    source_sha256: str
    source_root: str
    ids: dict[str, str]
    neoforge: str
    dependencies: tuple[Dependency, ...]
    artifact: str
    tasks: tuple[str, ...]
    game_report: str
    minimum_tests: int
    features: dict[str, str]
    limitations: tuple[str, ...]
    preview: bool
    target_property: str | None
    imports: dict[str, str] = field(default_factory=dict)
    asset_map: dict[str, tuple[str, str | None]] = field(default_factory=dict)
    locale_imports: dict[str, str] = field(default_factory=dict)

    def describe(self) -> dict:
        return {"original_sha256": self.legacy_sha256, "source_sha256": self.source_sha256,
                "status": "preview" if self.preview else "reviewed", "legacy_assets": "checked after build",
                "required_gametests": self.features, "limitations": list(self.limitations)}


def _parse_port(data: object, base: Path) -> SourcePort:
    row = _object(data)
    allowed = {"legacy_sha256", "source_sha256", "source_zip", "source_url", "source_root", "ids", "neoforge",
               "dependencies", "artifact", "tasks", "game_report", "minimum_tests", "features", "limitations",
               "status", "target_property", "import_assets", "asset_map", "locale_imports"}
    if extra := set(row) - allowed:
        raise InputError(f"Unknown recipe fields: {', '.join(sorted(extra))}")
    legacy, source = row.get("legacy_sha256"), row.get("source_sha256")
    if any(not isinstance(d, str) or not SHA256.fullmatch(d) for d in (legacy, source)):
        raise InputError("Both legacy_sha256 and source_sha256 must be lowercase SHA-256 digests")
    if ("source_zip" in row) == ("source_url" in row):
        raise InputError("Provide exactly one source_zip or immutable GitHub source_url")
    if "source_zip" in row:
        archive = base / _relative(row["source_zip"])
        if archive.suffix.lower() != ".zip":
            raise InputError("source_zip must point to a local .zip archive")
    else:
        archive = row["source_url"]
        if not isinstance(archive, str) or not SOURCE_URL.fullmatch(archive):
            raise InputError("source_url must be a codeload.github.com ZIP URL with a full commit SHA")
    root = row.get("source_root", "")
    if root != "" and (not isinstance(root, str) or not safe_component(root)):
        raise InputError("source_root must be a single directory at the root of the ZIP")
    ids = _object(row.get("ids"))
    if not ids or any(not MOD_ID.fullmatch(k) or k in RESERVED or not isinstance(v, str) or not VERSION.fullmatch(v)
                      for k, v in ids.items()):
        raise InputError("ids must specify exact native mod IDs and versions")
    neoforge = row.get("neoforge")
    if not isinstance(neoforge, str) or not neoforge.strip() or neoforge.strip() == "*" or len(neoforge) > 128:
        raise InputError("Specify a NeoForge version or version range")
    dependencies, seen = [], set()
    for dep in _list(row.get("dependencies", [])):
        dep = _object(dep)
        name, version, kind = dep.get("id"), dep.get("range", ""), dep.get("type", "required")
        if (dep.keys() - {"id", "range", "type"} or not isinstance(name, str) or not MOD_ID.fullmatch(name) or
                name in RESERVED or name in ids or not isinstance(version, str) or len(version) > 128 or
                kind not in ("required", "optional", "incompatible")):
            raise InputError(f"Invalid native dependency: {dep!r}")
        normalized = Dependency(name, version, kind)
        if normalized in seen:
            raise InputError(f"Duplicate native dependency: {dep!r}")
        seen.add(normalized)
        dependencies.append(normalized)
    artifact = _relative(row.get("artifact"))
    game_report = _relative(row.get("game_report"))
    if not artifact.endswith(".jar") or not game_report.endswith(".xml"):
        raise InputError("artifact must be a .jar and game_report a fresh NeoForge GameTest .xml")
    tasks = tuple(_list(row.get("tasks")))
    if not tasks or len(tasks) > 16 or any(not isinstance(t, str) or not TASK.fullmatch(t) for t in tasks):
        raise InputError("Specify Gradle task paths only (not commands or options)")
    minimum = row.get("minimum_tests", 1)
    features = _object(row.get("features"))
    if (type(minimum) is not int or not 1 <= minimum <= 20_000 or not features or
            any(not k.strip() or not isinstance(v, str) or not v.strip() for k, v in features.items())):
        raise InputError("Specify a positive minimum_tests and map reviewed features to exact GameTest case names")
    limitations = tuple(_list(row.get("limitations", [])))
    status = row.get("status")
    if (status not in ("reviewed", "preview") or any(not isinstance(s, str) or not s.strip() for s in limitations) or
            bool(limitations) != (status == "preview")):
        raise InputError("Use reviewed status for completed scope, or preview with documented limitations")
    target_property = row.get("target_property")
    if target_property is not None and (not isinstance(target_property, str) or
                                        not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*", target_property)):
        raise InputError("Invalid Gradle target_property name")
    imports = _object(row.get("import_assets", {}))
    asset_map = {}
    for old, entry in _object(row.get("asset_map", {})).items():
        _asset(old)
        entry = _object(entry)
        if set(entry) - {"path", "sha256"}:
            raise InputError(f"Unknown resource mapping fields for {old!r}")
        new, checksum = _asset(entry.get("path")), entry.get("sha256")
        if checksum is not None and (not isinstance(checksum, str) or not SHA256.fullmatch(checksum)):
            raise InputError(f"Invalid adapted asset hash for {old!r}")
        asset_map[old] = (new, checksum)
    outputs = set()
    for old, dest in imports.items():
        _asset(old)
        dest = _relative(dest)
        new = asset_map.get(old, (old, None))[0]
        if not _resource_dest(dest, new):
            raise InputError(f"Import destination for {old!r} must be its mapped path under src/main/resources/")
        if dest.casefold() in outputs:
            raise InputError(f"Duplicate asset import destination: {dest!r}")
        outputs.add(dest.casefold())
    locale_imports = _object(row.get("locale_imports", {}))
    for old, dest in locale_imports.items():
        _asset(old)
        if old.count("/") != 2 or not old.endswith("/lang") or not _resource_dest(_relative(dest), old):
            raise InputError("locale_imports must map assets/<namespace>/lang to a module's matching src/main/resources directory")
    return SourcePort(legacy, archive, source, root, ids, neoforge, tuple(dependencies), artifact, tasks,
                      game_report, minimum, features, limitations, status == "preview", target_property, imports, asset_map,
                      locale_imports)


def _resource_dest(dest: str, asset: str) -> bool:
    return ("/" + dest).endswith("/src/main/resources/" + asset)


def _asset(value: object) -> str:
    value = _relative(value)
    if not value.startswith("assets/") or value.count("/") < 2 or value.casefold().endswith((".class", ".jar", ".zip")):
        raise InputError(f"Expected an asset member path (assets/NAMESPACE/FILE): {value!r}")
    return value


def _list(value: object) -> list:
    if not isinstance(value, list):
        raise InputError("Expected a JSON array")
    return value


def load_ports(options: BuildOptions) -> dict[str, SourcePort]:
    manifest = options.manifest.expanduser().resolve()
    if not 1 <= options.timeout <= 3600:
        raise InputError("Use a source build timeout between 1 and 3600 seconds per project")
    if not manifest.is_file() or manifest.stat().st_size > 2 * 1024 * 1024:
        raise InputError("Specify a trusted local source-port JSON manifest (maximum 2 MiB)")
    try:
        data = _object(json.loads(manifest.read_text(encoding="utf-8-sig"), object_pairs_hook=_unique))
        if set(data) != {"schema_version", "ports"} or type(data["schema_version"]) is not int or data["schema_version"] != 1:
            raise InputError("Expected a version 1 source port manifest")
        result = {}
        for index, row in enumerate(_list(data["ports"]), 1):
            try:
                port = _parse_port(row, manifest.parent)
                if port.legacy_sha256 in result:
                    raise InputError("Multiple recipes for the same legacy SHA-256")
                result[port.legacy_sha256] = port
            except InputError as exc:
                raise InputError(f"Recipe {index}: {exc}") from exc
        return result
    except (ValueError, RecursionError) as exc:
        raise InputError(f"Invalid source port manifest: {exc}") from exc


class _NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def _copy_limited(src, dest: Path, limit: int) -> None:
    with dest.open("xb") as out:
        while chunk := src.read(256 * 1024):
            limit -= len(chunk)
            if limit < 0:
                raise InputError(f"Size limit exceeded writing {dest.name!r}")
            out.write(chunk)


def _source_zip(port: SourcePort, path: Path) -> None:
    if isinstance(port.archive, Path):
        if not port.archive.is_file():
            raise InputError(f"Source ZIP not found: {port.archive}")
        with port.archive.open("rb") as stream:
            _copy_limited(stream, path, MOD_ARCHIVE_LIMIT)
    else:
        try:
            with build_opener(_NoRedirect()).open(port.archive, timeout=40) as response:
                _copy_limited(response, path, MOD_ARCHIVE_LIMIT)
        except (URLError, TimeoutError) as exc:
            raise InputError(f"Could not download pinned source ZIP: {exc}") from exc
    if digest(path) != port.source_sha256:
        raise InputError("Source ZIP SHA-256 differs from the reviewed recipe")


def _unzip(path: Path, dest: Path, root: str) -> None:
    dest.mkdir()
    try:
        with zipfile.ZipFile(path) as archive:
            members = archive.infolist()
            if len(members) > MAX_PROJECT_FILES or sum(member.file_size for member in members) > MAX_PROJECT_BYTES:
                raise InputError("Source ZIP exceeds the source project size limit")
            seen = set()
            for member in members:
                name = _relative(member.orig_filename.removesuffix("/"))
                mode = stat.S_IFMT(member.external_attr >> 16)
                if mode not in {0, stat.S_IFREG, stat.S_IFDIR} or name in seen:
                    raise InputError(f"Duplicate or special file in the source ZIP: {name!r}")
                seen.add(name)
                if root:
                    if name == root and member.is_dir():
                        continue
                    if not name.startswith(root + "/"):
                        raise InputError(f"Unexpected ZIP entry outside source_root: {name!r}")
                    name = name[len(root) + 1:]
                if set(part.casefold() for part in name.split("/")) & {".git", ".gradle"}:
                    raise InputError(f"Source ZIP must not contain Git files or Gradle caches: {name!r}")
                if not member.is_dir():
                    if name.lower().endswith((".class", ".jar")) and name != "gradle/wrapper/gradle-wrapper.jar":
                        raise InputError(f"Source ZIP contains prebuilt output or bundled binaries: {name!r}")
                    target = dest / name
                    target.parent.mkdir(parents=True, exist_ok=True)
                    with archive.open(member) as src:
                        _copy_limited(src, target, member.file_size)
    except (zipfile.BadZipFile, zlib.error, FileExistsError, RuntimeError, EOFError) as exc:
        raise InputError(f"Could not extract source ZIP without overwriting files: {exc}") from exc


def _project_output(workspace: Path, member: str, limit: int) -> Path:
    output = workspace / member
    if (not output.is_file() or output.resolve() != output or output.stat().st_size > limit):
        raise InputError(f"Build did not create a safe, bounded output at {member!r}")
    return output


def _test_evidence(report: Path, port: SourcePort) -> dict:
    raw = report.read_bytes()
    try:
        if b"<!DOCTYPE" in raw.upper().replace(b"\x00", b""):
            raise InputError("DOCTYPE is not allowed in GameTest reports")
        root = ET.fromstring(raw)
        if root.tag not in {"testsuites", "testsuite"}:
            raise InputError("Expected a JUnit XML testsuite or testsuites root")
        cases = list(root.iter("testcase"))
        for node in root.iter():
            if node.tag in {"failure", "error", "skipped", "disabled"}:
                raise InputError(f"GameTest report contains a {node.tag} result")
            if node.tag in {"testsuites", "testsuite"} and (any(int(node.get(n, "0")) for n in
                    ("failures", "errors", "skipped", "disabled")) or ("tests" in node.attrib and
                    int(node.attrib["tests"]) != sum(1 for _ in node.iter("testcase")))):
                raise InputError("GameTest report has failures/skips or inaccurate case counts")
        if len({(case.get("classname", ""), case.get("name", "")) for case in cases}) != len(cases):
            raise InputError("GameTest report repeats a test identity")
        names = Counter(c.get("name", "") for c in cases)
        if len(cases) < port.minimum_tests or "" in names:
            raise InputError(f"Expected at least {port.minimum_tests} named NeoForge GameTests; found {len(cases)}")
        missing = sorted({name for name in port.features.values() if names[name] != 1})
        if missing:
            raise InputError("Required GameTest cases are missing or ambiguous: " + ", ".join(missing))
    except (ET.ParseError, ValueError) as exc:
        raise InputError(f"Invalid NeoForge GameTest report: {exc}") from exc
    return {"passed": len(cases), "covered_features": port.features, "report_sha256": hashlib.sha256(raw).hexdigest(),
            "scope": "Source project's NeoForge GameTest run; not a client or full-pack test"}


def _import_locales(source: ModInput, workspace: Path, port: SourcePort) -> tuple[dict, list]:
    """Convert original UTF-8 key=value files that lack an authored JSON locale; never guess key aliases."""
    adapted, evidence = {}, []
    generated = set()
    for prefix, directory in sorted(port.locale_imports.items()):
        for member in source.files:
            if not member.startswith(prefix + "/"):
                continue
            match = LOCALE_FILENAME.fullmatch(member[len(prefix) + 1:])
            if not match:
                continue
            leaf = match[1].lower() + ".json"
            dest = workspace / directory / leaf
            if dest in generated:
                raise InputError(f"More than one legacy locale would generate {leaf!r}")
            if dest.exists():
                continue  # Start with the authored native translation if there is one.
            pairs, skipped = _read_lang(source.text(member))
            values = dict(pairs)  # As in the legacy language registry, the last duplicate wins.
            duplicates = len(pairs) - len(values)
            if not values or (not port.preview and (skipped or duplicates)):
                raise InputError(f"Locale {member!r} needs review: {skipped} unsupported lines, {duplicates} duplicate entries")
            data = (json.dumps(values, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode("utf-8")
            dest.parent.mkdir(parents=True, exist_ok=True)
            with dest.open("xb") as stream:
                stream.write(data)
            output = prefix + "/" + leaf
            checksum = hashlib.sha256(data).hexdigest()
            if member in port.asset_map and port.asset_map[member] != (output, checksum):
                raise InputError(f"Locale conversion differs from the reviewed asset mapping for {member!r}")
            adapted[member] = (output, checksum)
            generated.add(dest)
            evidence.append({"original": member, "path": output, "keys": len(values), "sha256": checksum,
                             "unsupported_lines": skipped, "duplicate_entries": duplicates})
    return adapted, evidence


def _asset_evidence(original: Path, modern: Path, port: SourcePort, converted: dict) -> dict:
    counts = Counter()
    unverified = []
    with open_input(original) as before, open_input(modern) as after:
        old_assets = {name for name in before.files if name.startswith("assets/") and name.count("/") >= 2}
        if absent := (port.asset_map.keys() | port.imports.keys()) - old_assets:
            raise InputError(f"Reviewed mappings expect missing original assets: {', '.join(sorted(absent)[:5])}")
        mappings = port.asset_map | converted
        for name in sorted(old_assets):
            new, expected = mappings.get(name, (name, None))
            old = hashlib.sha256(before.read(name, MOD_ARCHIVE_LIMIT)).hexdigest()
            actual = hashlib.sha256(after.read(new, MOD_ARCHIVE_LIMIT)).hexdigest() if new in after.files else None
            if expected and actual != expected:
                raise InputError(f"Adapted asset {new!r} is missing or does not match its reviewed SHA-256")
            if actual == old:
                counts["retained"] += 1
            elif expected:
                counts["adapted"] += 1
            else:
                counts["unmapped"] += 1
                unverified.append({"original": name, "expected_path": new, "status": "missing" if actual is None else "changed"})
        # Reusing a namespace for new code is fine; shipping verbatim old bytecode isn't.
        for name in after.files:
            if name.endswith(".class") and not name.startswith("META-INF/versions/"):
                if name.startswith(("net/minecraft/", "net/minecraftforge/", "net/neoforged/")):
                    raise InputError(f"Produced JAR bundles platform classes: {name}")
                head = after.read(name, CLASS_LIMIT)[:8]
                if len(head) != 8 or not head.startswith(b"\xca\xfe\xba\xbe") or not 45 <= int.from_bytes(head[6:], "big") <= 65:
                    raise InputError(f"Class {name!r} is not supported by Java 21")
        for name in before.files.keys() & after.files.keys():
            if name.endswith(".class") and before.read(name, CLASS_LIMIT) == after.read(name, CLASS_LIMIT):
                raise InputError(f"Produced JAR contains unchanged legacy bytecode: {name}")
    if unverified and not port.preview:
        examples = ", ".join(item["original"] for item in unverified[:5])
        raise InputError(f"{len(unverified)} legacy asset path(s) missing or changed without a reviewed mapping: {examples}")
    return {"original_count": len(old_assets), "retained": counts["retained"], "adapted": counts["adapted"],
            "unmapped": counts["unmapped"], "unmapped_examples": unverified[:30]}


def _java_bin(name: str) -> str:
    if home := os.environ.get("JAVA_HOME"):
        return str(Path(home) / "bin" / (name + (".exe" if os.name == "nt" else "")))
    return name


def require_jdk() -> str:
    try:
        javac = subprocess.run([_java_bin("javac"), "-version"], capture_output=True, text=True, timeout=15, check=True)
        java = subprocess.run([_java_bin("java"), "-version"], capture_output=True, text=True, timeout=15, check=True)
    except (OSError, subprocess.SubprocessError) as exc:
        raise InputError("Source builds need JDK 21. Set JAVA_HOME to a JDK 21 installation or add its bin/ to PATH.") from exc
    if not re.search(r"\bjavac 21\b", javac.stdout + javac.stderr) or not re.search(r'\bversion "21[."+-]', java.stderr + java.stdout):
        raise InputError("Source builds need both java and javac from JDK 21.")
    return _java_bin("java")


def _gradle(port: SourcePort, workspace: Path, java: str, deps: Path, neoforge: str, timeout: int) -> None:
    wrapper = workspace / "gradle/wrapper/gradle-wrapper.jar"
    properties = wrapper.with_suffix(".properties")
    if not wrapper.is_file() or not properties.is_file() or not re.search(
            r"^distributionSha256Sum\s*=\s*[a-fA-F0-9]{64}\s*$", properties.read_text(encoding="utf-8"), re.M):
        raise InputError("The reviewed source ZIP needs a Gradle wrapper with a distribution SHA-256 pin")
    args = [java, "-cp", str(wrapper), "org.gradle.wrapper.GradleWrapperMain", "--no-daemon", "--no-build-cache",
            "--console=plain", "--max-workers=2", f"-PmcmodupDependencyDir={deps}", f"-PmcmodupTargetNeoForge={neoforge}"]
    if port.target_property is not None:
        args.append(f"-P{port.target_property}={neoforge}")
    log = workspace.parent / "gradle.log"
    with log.open("wb") as stream, subprocess.Popen([*args, *port.tasks], cwd=workspace, stdin=subprocess.DEVNULL,
                                                     stdout=stream, stderr=subprocess.STDOUT,
                                                     start_new_session=os.name != "nt") as proc:
        try:
            code = proc.wait(timeout=timeout)
        except BaseException as exc:
            if os.name == "nt":
                proc.kill()
            else:
                try:
                    os.killpg(proc.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
            if isinstance(exc, subprocess.TimeoutExpired):
                raise InputError(f"Gradle source build exceeded {timeout} seconds") from exc
            raise
    if code:
        with log.open("rb") as stream:
            stream.seek(max(0, log.stat().st_size - 9000))
            tail = stream.read().decode("utf-8", errors="replace").strip()
        raise InputError(f"Gradle source build failed (exit {code}):\n{tail}")


def build(port: SourcePort, original: Path, task_dir: Path, dependencies: list[tuple[Path, str]],
          neoforge: str, java: str, timeout: int) -> tuple[Path, dict]:
    """Compile in a new scratch tree; the original, selected dependencies and source ZIP are read-only inputs."""
    task_dir.mkdir()
    orig = task_dir / "original.jar"
    with original.open("rb") as src:
        _copy_limited(src, orig, MOD_ARCHIVE_LIMIT)
    if digest(orig) != port.legacy_sha256:
        raise InputError("Legacy archive changed or does not match this recipe")
    zipped, workspace, deps = (task_dir / key for key in ("source.zip", "project", "dependencies"))
    _source_zip(port, zipped)
    _unzip(zipped, workspace, port.source_root)
    zipped.unlink()
    if (workspace / port.artifact).exists() or (workspace / port.game_report).exists():
        raise InputError("A source ZIP must not contain its prebuilt output JAR or GameTest report")
    with open_input(orig) as source:
        if source.warnings:
            raise InputError("Cannot verify assets of an original archive with duplicate/unsafe entries")
        for member, dest in port.imports.items():
            if member not in source.files:
                raise InputError(f"Original asset {member!r} not found")
            target = workspace / dest
            target.parent.mkdir(parents=True, exist_ok=True)
            with target.open("xb") as handle:
                handle.write(source.read(member, MOD_ARCHIVE_LIMIT))
        converted, locales = _import_locales(source, workspace, port)
    deps.mkdir()
    copied = {}
    for path, checksum in dependencies:
        target = deps / path.name
        shutil.copyfile(path, target)
        if digest(target) != checksum:
            raise InputError(f"Native dependency {path.name!r} changed during the build")
        copied[path.name] = checksum
    _gradle(port, workspace, java, deps, neoforge, timeout)
    if digest(orig) != port.legacy_sha256 or any(digest(deps / name) != checksum for name, checksum in copied.items()):
        raise InputError("Build changed the supplied legacy or dependency input copies")
    output = _project_output(workspace, port.artifact, MOD_ARCHIVE_LIMIT)
    evidence = {**port.describe(), "archive": str(port.archive) if isinstance(port.archive, str) else port.archive.name,
                "dependencies_sha256": copied, "imports": len(port.imports), "converted_locales": locales,
                "gametests": _test_evidence(_project_output(workspace, port.game_report, 8 * 1024 * 1024), port),
                "legacy_assets": _asset_evidence(orig, output, port, converted)}
    return output, evidence
