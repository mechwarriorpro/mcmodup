"""Resolve one coherent set of native archives, including transitive version constraints."""

from collections import defaultdict
from collections.abc import Callable
from dataclasses import dataclass
from itertools import groupby
from typing import TYPE_CHECKING

from .versions import matches, ranges_disjoint, supported_range

if TYPE_CHECKING:
    from .native_pack import NativeMod

_MAX_STATES = 2000
_MAX_BRANCH_DEPTH = 80


def dependency_requests(mods: tuple["NativeMod", ...] | list["NativeMod"]):
    requests = defaultdict(list)
    for mod in sorted(mods, key=lambda mod: mod.label):
        for dep in mod.dependencies:
            requests[dep.mod_id].append({"archive": mod.label, "range": dep.version, "type": dep.kind})
    return requests


def _fits(version: str, rows: list[dict]) -> bool:
    return all(supported_range(row["range"]) and matches(version, row["range"]) == (row["type"] != "incompatible")
               for row in rows)


def _describe(rows: list[dict]) -> str:
    return "; ".join(f"{row['archive']} {row['type']} {row['range'] or '*'}" for row in rows)


@dataclass
class Resolution:
    mods: tuple["NativeMod", ...]
    status: str = "ready"
    problem: str = ""


def resolve(roots: set[str], installed: list["NativeMod"], providers: dict[str, list["NativeMod"]],
            forced: dict[str, "NativeMod"], pins: dict[str, str], *, keep_target: bool,
            artifact_errors: Callable[[tuple["NativeMod", ...]], list[str]]) -> Resolution:
    retained = defaultdict(list)
    if keep_target:
        for mod in installed:
            for key in mod.ids:
                retained[key].append(mod)
        for key, choices in sorted(retained.items()):
            if len(choices) > 1:
                return Resolution(tuple(installed), "blocked", f"Target has multiple installed providers for {key!r}: " +
                                  ", ".join(mod.label for mod in choices) + ".")
    goals = roots | retained.keys()
    remaining = _MAX_STATES

    def priority(mod: "NativeMod") -> int:
        return 0 if mod.installed else 2 if mod.recipe else 1

    def blocks_partial(mod: "NativeMod", key: str, rows: list[dict], required: set[str]) -> bool:
        if not mod.partial:
            return False
        if mod.recipe and mod.recipe.isolated_preview:
            return (forced.get(key) is not mod or key not in roots or
                    any(row["type"] in {"required", "optional"} for row in rows))
        return key in required

    def solve(mods: tuple["NativeMod", ...], depth: int) -> Resolution:
        nonlocal remaining
        while True:
            remaining -= 1
            if remaining < 0 or depth > _MAX_BRANCH_DEPTH:
                return Resolution(mods, "limit", "Local dependency search exceeded its limit. Pin reviewed providers with --provider ID=SHA256.")
            active = {}
            for mod in mods:
                for key in mod.ids:
                    if key in active:
                        return Resolution(mods, "blocked", f"Conflicting archives for {key!r}: {active[key].label}, {mod.label}.")
                    active[key] = mod
            requests = dependency_requests(mods)
            required = roots | {key for key, rows in requests.items() if any(row["type"] == "required" for row in rows)}
            needed = goals | required
            for key, mod in sorted(active.items()):
                rows = requests[key]
                if blocks_partial(mod, key, rows, required):
                    return Resolution(mods, "blocked", f"{mod.label!r} is a partial port and cannot supply {key!r}.")
                if not _fits(mod.ids[key], rows):
                    incompatible = any(row["type"] == "incompatible" and matches(mod.ids[key], row["range"]) for row in rows)
                    detail = "declared incompatibility" if incompatible else "unsatisfied version requirement"
                    return Resolution(mods, "blocked", f"{key!r} {mod.ids[key]}: {detail}. {_describe(rows)}.")

            unique = {}
            options = []
            for key in sorted(needed - active.keys()):
                choices = {}
                pool = [*providers.get(key, ()), *retained.get(key, ())]
                if key in forced:
                    pool.append(forced[key])
                available = sorted(dict.fromkeys(pool), key=lambda mod: (priority(mod), mod.label))
                for mod in available:
                    if any(
                        name in active or (name in forced and forced[name] is not mod) or
                        (name in pins and pins[name] != mod.sha256) or
                        blocks_partial(mod, name, requests[name], required) or
                        (name in retained and not retained[name][0].ids.keys() <= mod.ids.keys()) or
                        not _fits(version, requests[name])
                        for name, version in mod.ids.items()
                    ) or (not mod.target_evidence and not (keep_target and mod.installed)):
                        continue
                    choices.setdefault(mod.sha256, mod)
                if not choices:
                    rows = requests[key]
                    positive = [row["range"] for row in rows if row["type"] in {"required", "optional"}]
                    reason = (" No shared version satisfies the disjoint ranges; use a reviewed native adapter or privately relocate a pure Java library."
                              if ranges_disjoint(positive) else "")
                    expected = f" (pinned SHA-256 {pins[key]})" if key in pins else ""
                    found = ", ".join(f"{item.label} ({item.ids[key] or 'unknown version'})" for item in available)
                    message = (f"No compatible standalone NeoForge JAR for {key!r}{expected}." +
                               (f" Requires: {_describe(rows)}." if rows else "") + (f" Found: {found}." if found else "") + reason)
                    return Resolution(mods, "blocked", message)
                if len(choices) == 1:
                    choice = next(iter(choices.values()))
                    unique[choice] = None
                else:
                    options.append((key, list(choices.values())))
            if unique:
                mods += tuple(unique)
                continue
            if not options:
                if errors := artifact_errors(mods):
                    return Resolution(mods, "blocked", errors[0])
                return Resolution(mods)

            key, choices = min(options, key=lambda pair: (len(pair[1]), pair[0]))
            blocked = None
            for _, tier in groupby(choices, key=priority):
                found = None
                for choice in tier:
                    outcome = solve((*mods, choice), depth + 1)
                    if outcome.status == "limit":
                        return outcome
                    if outcome.status == "blocked":
                        if blocked is None or len(outcome.mods) > len(blocked.mods):
                            blocked = outcome
                        continue
                    if found:
                        previous, evidence = found
                        return Resolution(evidence.mods, "ambiguous",
                                          f"Multiple compatible local JARs for {key!r}: {previous.label}, {choice.label}. "
                                          "Pin the reviewed version with --provider ID=SHA256.")
                    found = choice, outcome
                if found:
                    return found[1]
            assert blocked is not None
            return blocked

    return solve((), 0)


def dependency_report(resolution: Resolution, providers: dict[str, list["NativeMod"]]) -> list[dict]:
    active = defaultdict(list)
    for mod in resolution.mods:
        for key in mod.ids:
            active[key].append(mod)
    result = []
    for key, requests in sorted(dependency_requests(resolution.mods).items()):
        required = any(row["type"] == "required" for row in requests)
        positive = [row["range"] for row in requests if row["type"] in {"required", "optional"}]
        installed = active[key]
        invalid = len(installed) != 1 or installed[0].partial or not _fits(installed[0].ids[key], requests)
        if not required and not installed:
            status = "not_selected"
        elif ranges_disjoint(positive):
            status = "disjoint_native_ranges"
        elif invalid:
            status = "unresolved"
        elif len({r["archive"] for r in requests if r["type"] == "required"}) > 1:
            status = "shared_native_provider"
        else:
            status = "native_provider"

        def artifact(mod: "NativeMod") -> dict:
            return {"archive": mod.label, "version": mod.ids[key], "sha256": None if mod.recipe else mod.sha256}

        result.append({"id": key, "status": status, "requirements": requests,
                       "provider": artifact(installed[0]) if not invalid else None,
                       "candidates": [artifact(mod) | {"matches_metadata": _fits(mod.ids[key], requests)}
                                      for mod in providers.get(key, ())]})
    return result
