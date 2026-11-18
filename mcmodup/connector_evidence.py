"""Read local Legacy Connector evidence without running or depending on Connector."""

import hashlib
import json
from pathlib import Path, PurePosixPath

from .inputs import InputError
from .pack import PackMod


REPORT_LIMIT = 32 * 1024 * 1024
RESULTS = {"links", "unresolved", "missing-dependency", "obsolete", "skipped", "not-legacy"}


def _document(path: Path) -> tuple[object, dict]:
    path = path.expanduser().resolve()
    with path.open("rb") as stream:
        data = stream.read(REPORT_LIMIT + 1)
    if len(data) > REPORT_LIMIT:
        raise InputError(f"Connector evidence is larger than {REPORT_LIMIT // (1024 * 1024)} MiB: {path}")
    try:
        return json.loads(data.decode("utf-8-sig")), {"file": str(path), "sha256": hashlib.sha256(data).hexdigest()}
    except (ValueError, RecursionError) as exc:
        raise InputError(f"{path}: invalid Connector JSON: {exc}") from exc


def _rows(data, key: str, label: str) -> list[dict]:
    if not isinstance(data, list) or any(not isinstance(row, dict) or not isinstance(row.get(key), str) for row in data):
        raise InputError(f"{label}: expected a JSON array of records with string {key!r} values.")
    return data


def _release(value: object, minecraft: str) -> str:
    # Some mcmod.info files (for example Thaumcraft) prefix the exact Minecraft
    # version to a release that @Mod and CheckJar report without that prefix.
    text = value.strip().removeprefix(f"{minecraft}-") if isinstance(value, str) else ""
    # An unresolved build token is not evidence that two versions agree or disagree.
    return "" if text in {"?", "unknown", "0.0.0-legacy"} or text.startswith(("@", "${")) else text


def _check_record(row: dict, label: str) -> None:
    def fields(record: dict, counts: tuple[str, ...], lists: tuple[str, ...]) -> None:
        for key in counts:
            if key in record and (type(record[key]) is not int or record[key] < 0):
                raise InputError(f"{label}: expected a nonnegative count in {key!r}.")
        for key in lists:
            if key in record and (not isinstance(record[key], list) or any(not isinstance(x, str) for x in record[key])):
                raise InputError(f"{label}: expected a string list in {key!r}.")

    fields(row, ("classes", "unresolved", "synthesizedClasses", "synthesizedMembers", "verifyFailures"),
           ("missing", "stubbed", "optional", "missingDependencies", "verifyErrors"))
    for key in ("reason", "coremod"):
        if key in row and not isinstance(row[key], str):
            raise InputError(f"{label}: expected text in {key!r}.")
    for key in ("verify", "assets"):
        if key in row and not isinstance(row[key], dict):
            raise InputError(f"{label}: expected an object in {key!r}.")
    verify = row.get("verify", {})
    fields(verify, ("classes", "methods", "findings", "groupCount", "unknownTypes", "methodsSkipped"), ())
    if "groups" in verify:
        for group in _rows(verify["groups"], "problem", f"{label}: verify.groups"):
            fields(group, ("sites",), ("examples",))
    if "assets" in row:
        fields(row["assets"], ("missingTextures", "missingModels", "layer", "mod", "badJson", "badPng"), ("examples",))


def linkage(path: Path, mod: PackMod, minecraft: str) -> dict:
    data, origin = _document(path)
    label = origin["file"]
    filename = PurePosixPath(mod.archive).name
    candidates = [row for row in _rows(data, "jar", f"{label}: expected raw CheckJar output") if row["jar"] == filename]
    if len(candidates) != 1:
        detail = "no record" if not candidates else "ambiguous records"
        raise InputError(f"{label}: {detail} for selected JAR {filename!r}; run CheckJar on the exact original and its peers.")
    row = candidates[0]
    result = row.get("result")
    if not isinstance(result, str) or result not in RESULTS:
        raise InputError(f"{label}: unrecognized CheckJar result for {filename!r}.")
    recorded_mc = row.get("mc")
    if recorded_mc != minecraft and not (recorded_mc is None and result in {"skipped", "not-legacy"}):
        raise InputError(f"{label}: CheckJar Minecraft {recorded_mc!r} does not match selected Minecraft {minecraft}.")
    _check_record(row, label)
    recorded_mods = {r["id"].casefold(): _release(r.get("version"), minecraft)
                     for r in _rows(row.get("mods", []), "id", f"{label}: mods") if r["id"]}
    local_mods = {m.mod_id.casefold(): _release(m.version, minecraft) for m in mod.audit.mods if m.mod_id}
    shared = local_mods.keys() & recorded_mods.keys()
    if local_mods and recorded_mods and not shared:
        raise InputError(f"{label}: CheckJar mod IDs do not match {filename!r}.")
    for mod_id in sorted(shared):
        old, observed = local_mods[mod_id], recorded_mods[mod_id]
        if old and observed and old != observed:
            raise InputError(f"{label}: CheckJar release {observed!r} of {mod_id} does not match local release {old!r}.")
    return origin | {"matched_by": ["filename"] + (["minecraft"] if recorded_mc else [])
                     + (["available mod IDs/versions"] if shared else []),
                     "binary_identity_verified": False, "record": row}


def gameplay(path: Path, mod: PackMod, minecraft: str) -> dict:
    """Import only same-release human/client observations, not site probe/corpus status."""
    data, origin = _document(path)
    title = f"{origin['file']}: expected Legacy Connector docs/data/mods.json with a 'mods' array"
    rows = _rows(data.get("mods") if isinstance(data, dict) else None, "name", title)
    local = {(m.name.strip().casefold(), _release(m.version, minecraft)) for m in mod.audit.mods if m.name and _release(m.version, minecraft)}
    obs = []
    for row in rows:
        if row.get("mc") != minecraft or (row["name"].strip().casefold(), _release(row.get("version"), minecraft)) not in local:
            continue
        source = row.get("source")
        if source not in ("client", "server", None) or not isinstance(row.get("notes"), str) or not row["notes"].strip():
            continue
        obs.append({key: row[key] for key in ("name", "version", "mc", "source", "date", "status", "notes")
                    if isinstance(row.get(key), str)})
    return origin | {"matched_by": ["display name", "mod version", "minecraft"], "binary_identity_verified": False,
                     "observations": obs}
