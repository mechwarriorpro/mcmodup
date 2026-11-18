"""Read source trees and ZIP-format mod archives without executing or extracting them."""

from collections.abc import Iterator
from contextlib import contextmanager
from dataclasses import dataclass, field
import os
from pathlib import Path
import stat
import zipfile
import zlib


TEXT_LIMIT = 2 * 1024 * 1024
IGNORED_DIRS = {".git", ".gradle", ".idea", "build", "bin", "out", "target", "run", "runs", "__MACOSX"}


class InputError(ValueError):
    """A problem with a user-supplied path, archive, or option."""


@dataclass
class ModInput:
    path: Path
    files: dict[str, Path | zipfile.ZipInfo]
    archive: zipfile.ZipFile | None = None
    warnings: list[str] = field(default_factory=list)

    def read(self, name: str, limit: int = TEXT_LIMIT) -> bytes:
        entry = self.files[name]
        try:
            if isinstance(entry, zipfile.ZipInfo):
                if entry.file_size > limit:
                    raise InputError(f"exceeds the {limit}-byte read limit")
                stream = self.archive.open(entry)
            else:
                stream = entry.open("rb")
            with stream:
                content = stream.read(limit + 1)
        except (OSError, EOFError, zipfile.BadZipFile, zlib.error, RuntimeError) as exc:
            raise InputError(str(exc)) from exc
        if len(content) > limit:
            raise InputError(f"exceeds the {limit}-byte read limit")
        return content

    def text(self, name: str) -> str:
        try:
            return self.read(name).decode("utf-8-sig")
        except UnicodeDecodeError as exc:
            raise InputError("not UTF-8") from exc


def _directory_files(path: Path) -> dict[str, Path]:
    def onerror(exc: OSError) -> None:
        raise exc

    members = {}
    for root, dirs, files in os.walk(path, onerror=onerror):
        base = Path(root)
        dirs[:] = sorted(d for d in dirs if d not in IGNORED_DIRS and not (base / d).is_symlink())
        for filename in sorted(files):
            candidate = base / filename
            if not candidate.is_symlink() and candidate.is_file():
                members[candidate.relative_to(path).as_posix()] = candidate
    return dict(sorted(members.items()))


def _archive_files(archive: zipfile.ZipFile) -> tuple[dict[str, zipfile.ZipInfo], list[str]]:
    files = {}
    duplicates = set()
    ignored = 0
    for entry in archive.infolist():
        if entry.is_dir():
            continue
        parts = entry.filename.split("/")
        if (
            "\\" in entry.filename
            or any(part in {"", ".", ".."} for part in parts)
            or stat.S_ISLNK(entry.external_attr >> 16)
        ):
            ignored += 1
            continue
        if any(part in IGNORED_DIRS for part in parts[:-1]):
            continue
        if entry.filename in files:
            duplicates.add(entry.filename)
        files[entry.filename] = entry
    warnings = [f"Skipped duplicate archive entry {name!r}." for name in sorted(duplicates)]
    if ignored:
        warnings.append(f"Skipped {ignored} unsafe archive member name(s) or symbolic link(s).")
    return {name: files[name] for name in sorted(files.keys() - duplicates)}, warnings


@contextmanager
def open_input(path: Path) -> Iterator[ModInput]:
    path = path.expanduser().resolve()
    if path.is_dir():
        yield ModInput(path, _directory_files(path))
        return
    if not path.is_file() or path.suffix.lower() not in {".jar", ".zip"}:
        raise InputError(f"Expected a source directory or a .jar/.zip file: {path}")
    if path.stat().st_size > 256 * 1024 * 1024:
        raise InputError("Archives larger than 256 MiB are not supported; use an unpacked source directory.")
    try:
        archive = zipfile.ZipFile(path)
    except zipfile.BadZipFile as exc:
        raise InputError(f"Not a readable ZIP/JAR archive: {path}") from exc
    with archive:
        files, warnings = _archive_files(archive)
        yield ModInput(path, files, archive, warnings)
