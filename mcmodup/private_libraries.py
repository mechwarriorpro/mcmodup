"""Validate the isolation boundary of libraries relocated by a native port's build."""

from collections import Counter
from dataclasses import dataclass
import json
import re

from .classfiles import CLASS_LIMIT, CLASS_NAME, class_entries, read_class
from .inputs import InputError, ModInput
from .source_ports import MOD_ID, SHA256
from .versions import declared_version

PRIVATE_METADATA = "META-INF/mcmodup/private-libraries.json"
PRIVATE_ROOT = "mcmodup_private/"


@dataclass(frozen=True)
class PrivateLibrary:
    consumer: str
    id: str
    version: str
    original_package: str
    source_sha256: str
    classes: int

    @property
    def namespace(self) -> str:
        version = re.sub(r"[^a-zA-Z0-9]", "_", self.version)
        return f"{PRIVATE_ROOT}{self.consumer}/{self.id}/v{version}/"

    def report(self) -> dict:
        return {"consumer": self.consumer, "id": self.id, "version": self.version, "namespace": self.namespace,
                "original_package": self.original_package, "declared_source_sha256": self.source_sha256,
                "classes": self.classes, "isolation": "static_boundary_checked", "gameplay_verified": False}


def inspect(jar: ModInput, ids: dict[str, str], shared_ids: set[str]) -> tuple[PrivateLibrary, ...]:
    if PRIVATE_METADATA not in jar.files:
        return ()
    try:
        metadata = json.loads(jar.text(PRIVATE_METADATA))
    except (ValueError, RecursionError) as exc:
        raise InputError(f"Invalid private library manifest: {exc}") from exc
    rows = metadata.get("libraries") if isinstance(metadata, dict) else None
    if (not isinstance(rows, list) or not 1 <= len(rows) <= 32 or metadata.get("schema_version") != 1 or
            metadata.keys() != {"schema_version", "libraries"}):
        raise InputError("Expected a version 1 private library manifest")
    libs = []
    seen = set()
    for row in rows:
        if (not isinstance(row, dict) or row.keys() != {"consumer", "id", "version", "original_package", "source_sha256"} or
                not all(isinstance(x, str) for x in row.values())):
            raise InputError("Each relocated library needs consumer, id, version, original_package, and source_sha256")
        consumer, key, version, package, sha = (row[name] for name in ("consumer", "id", "version", "original_package", "source_sha256"))
        if (consumer not in ids or (consumer, key) in seen or not MOD_ID.fullmatch(key) or key in ids or key in shared_ids or
                not declared_version(version) or not SHA256.fullmatch(sha) or not CLASS_NAME.fullmatch(package) or
                "/" not in package or package.startswith((PRIVATE_ROOT, "java/", "javax/", "net/minecraft/", "net/neoforged/"))):
            raise InputError(f"Invalid or globally declared private library: {key!r} for {consumer!r}")
        seen.add((consumer, key))
        libs.append(PrivateLibrary(consumer, key, version, package, sha, 0))

    counts, used = Counter(), set()
    prefixes = {lib.namespace: lib for lib in libs}
    originals = tuple(lib.original_package + "/" for lib in libs)
    entries = class_entries(jar.files)
    for owner, entry in entries.items():
        lib = next((lib for prefix, lib in prefixes.items() if owner.startswith(prefix)), None)
        if owner.startswith(originals) or (owner.startswith(PRIVATE_ROOT) and lib is None):
            raise InputError(f"Unrelocated or undeclared private class {owner!r}")
        info = read_class(jar.read(entry, CLASS_LIMIT))
        if info.owner != owner:
            raise InputError(f"Private library boundary check: {entry!r} declares {info.owner!r}")
        for ref in info.references:
            if ref.owner.startswith(originals):
                raise InputError(f"{owner!r} still refers to unrelocated library class {ref.owner!r}")
            if not lib and ref.owner in entries and ref.owner.startswith(PRIVATE_ROOT):
                used.update(x for x in libs if ref.owner.startswith(x.namespace))
            if ref.owner.startswith(PRIVATE_ROOT) and ref.owner not in entries:
                raise InputError(f"{owner!r} refers to missing private class {ref.owner!r}")
            if lib and not ref.owner.startswith((lib.namespace, "java/", "javax/")):
                raise InputError(f"{owner!r} is not a private Java library: it references {ref.owner!r}")
            if lib and (ref.owner in {"java/util/ServiceLoader", "java/lang/ClassLoader"} or
                        ref.owner.startswith("java/lang/reflect/") or
                        (ref.owner in {"java/lang/Class", "java/lang/System", "java/lang/Runtime"} and
                         ref.name in {"forName", "load", "loadLibrary"})):
                raise InputError(f"Reflective, native, or service loading requires a manually ported boundary: {owner!r}")
        if lib:
            if any(flags & 0x0100 for flags in info.members.values()):
                raise InputError(f"JNI code cannot be treated as a private Java library: {owner!r}")
            counts[lib] += 1

    for name in jar.files:
        if name.startswith(originals) or (name.startswith(PRIVATE_ROOT) and not name.startswith(tuple(prefixes))):
            raise InputError(f"Unrelocated or undeclared private resource {name!r}")
        if name.startswith("META-INF/versions/") and any(part in name for part in (PRIVATE_ROOT, *originals)):
            raise InputError(f"Multi-release private library overlays are not supported: {name!r}")
        if name.startswith("META-INF/services/") and PRIVATE_ROOT.replace("/", ".") in (name + jar.text(name)):
            raise InputError(f"Private libraries must not register global services: {name!r}")
    if any(lib not in used for lib in libs):
        raise InputError("A private library claim must contain relocated classes referenced by the native consumer")
    return tuple(PrivateLibrary(lib.consumer, lib.id, lib.version, lib.original_package, lib.source_sha256, counts[lib]) for lib in libs)
