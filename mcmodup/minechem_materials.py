"""Replay release-specific Minechem material rules captured from the pinned Forge registries."""
from dataclasses import dataclass
from functools import cache
import math
from pathlib import Path
import re

from .inputs import InputError

TEMPLATES = Path(__file__).with_name("templates") / "minechem"
PRODUCT = re.compile(r"([em]\d{1,3})x([1-9]\d{0,3})")


def observations(name: str) -> dict[str, str]:
    rows = {}
    for line in (TEMPLATES / name).read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        key, sep, content = line.partition("=")
        if not sep or not key or key in rows or any(char in key for char in "|;\t@"):
            raise InputError(f"Invalid or duplicate Minechem observation in {name}: {line}")
        rows[key] = content
    return rows


def chemical_id(key: str, ids: dict[str, str]) -> str:
    if key not in ids:
        raise InputError(f"Could not map original Minechem chemical metadata {key}.")
    return ids[key]


@cache
def vanilla_ids() -> dict[str, str]:
    return observations("vanilla-material-ids.txt")


_NATIVE_394 = {"cPolytool:0": "polytool", "ctile.chemicalDecomposer:0": "decomposer",
               "ctile.chemicalSynthesizer:0": "synthesizer", "ctile.opticalMicroscope:0": "microscope",
               "ctile.oreUranium:0": "uranium_ore", "ctile.leadChest:0": "leaded_chest"}
_PAIRED_394 = {"citem.opticalMicroscopeLens:0": "concave_lens", "citem.opticalMicroscopeLens:1": "convex_lens",
                "citem.opticalMicroscopeLens:2": "microscope_lens", "citem.opticalMicroscopeLens:3": "projector_lens",
                "ctile.blueprintProjector:0": "blueprint_projector", "cminechem.FusionWall:0": "reactor_casing",
                "cminechem.TungstenPlating:1": "reactor_coil"}


def item_id(key: str, namespace: str, ids: dict[str, str], paired: bool = False) -> str | None:
    if re.fullmatch(r"[em]\d{1,3}", key):
        return chemical_id(key, ids)
    if key.startswith("v"):
        item = vanilla_ids().get(key)
        if item is None:
            raise InputError(f"Missing review for original material identity {key}")
        return item if item != "-" else None
    if re.fullmatch(r"b[em]\d{1,3}", key):
        chemical = chemical_id(key[1:], ids).split(":", 1)[1]
        return f"{namespace}:bucket_{chemical}"
    if key == "ctile.minechem.uraniumOre:0":
        return f"{namespace}:uranium_ore"
    path = _NATIVE_394.get(key) or (_PAIRED_394.get(key) if paired else None)
    return f"{namespace}:{path}" if path else None


@dataclass(frozen=True)
class Node:
    parts: tuple[tuple[str, int], ...] = ()
    chance: float = 1.0
    choices: tuple["Node", ...] = ()
    refs: tuple[tuple[str, float], ...] = ()

    def encode(self) -> str:
        values = ",".join(f"{item}@{count}" for item, count in self.parts) or "-"
        if self.choices:
            return f"S{self.chance}!" + "|".join(choice.encode() for choice in self.choices)
        if self.refs:
            return f"R{values}|" + "|".join(f"{key}@{weight}" for key, weight in self.refs)
        return f"C{self.chance}!{values}" if self.chance != 1 else values


def parse(data: str, ids: dict[str, str]) -> Node:
    def parts(raw: str) -> tuple[tuple[str, int], ...]:
        values = []
        for value in [] if raw == "-" else raw.split(","):
            part = PRODUCT.fullmatch(value)
            if not part:
                raise InputError(f"Invalid decomposition product {value!r}")
            values.append((chemical_id(part[1], ids), int(part[2])))
        return tuple(values)

    if data.startswith(("S", "C")):
        likelihood, rest = data[1:].split("!", 1)
        chance = float(likelihood)
        if not 0 <= chance <= 1:
            raise InputError(f"Out of range decomposition chance {likelihood}")
        if data.startswith("S"):
            return Node(chance=chance, choices=tuple(parse(choice, ids) for choice in rest.split("|")))
        return Node(parts(rest), chance=chance)
    if data.startswith("R"):
        base, *rest = data[1:].split("|")
        refs = []
        for entry in rest:
            key, weight = entry.rsplit("@", 1)
            if not math.isfinite(float(weight)) or not 0 < float(weight) <= 64:
                raise InputError(f"Out of range decomposition weight {entry}")
            refs.append((key, float(weight)))
        return Node(parts(base), refs=tuple(refs))
    return Node(parts(data))


@dataclass(frozen=True)
class Material:
    key: str
    id: str
    volume: int
    representative: tuple[tuple[str, int], ...]


def release(namespace: str, ids: dict[str, str], *, prepaid: bool, paired: bool = False,
            ordered: dict[str, tuple[tuple[str, int], ...]] | None = None) -> tuple[list[Material], dict[str, Node]]:
    prefix = "release394" if prepaid else "final"
    rows = observations(f"{prefix}-materials.txt")
    # Resolve Forge fluid names against the exact release; do not admit external or other-release fluids.
    from .minechem_matter import fluid_rows
    fluids = fluid_rows(prepaid, ids)
    native_fluids = {}
    if prepaid:
        matter = observations(f"{prefix}-fluids.txt")
        for fluid in fluids:
            legacy = f"f{fluid.legacy_name}:125"
            # Two old fluids share the name mt. Keep both containers, using the observed tank lookup.
            key = legacy if legacy not in native_fluids else f"f{fluid.key}:125"
            rows[key] = matter.get(f"alias_{fluid.key}", rows[legacy])
            native_fluids[key] = fluid.fluid
            if f"b{fluid.key}" not in matter:
                raise InputError(f"Missing .394 bucket recipe observation for {fluid.key}")
            rows[f"b{fluid.key}"] = matter[f"b{fluid.key}"]
    else:
        ore_key, ore_yield = observations(f"{prefix}-radiation.txt")["ore"].split("/", 1)
        rows[f"c{ore_key}:0"] = ore_yield
    nodes = {key: parse(raw, ids) for key, raw in rows.items()}
    if {key for key in ids if key.startswith("m")} != {key for key in nodes if re.fullmatch(r"m\d+", key)}:
        raise InputError(f"The {prefix} molecule catalog does not match the measured decomposition inputs.")
    # Retain the measured emission order of existing .394 machine-level replays.
    if ordered:
        for key, original in nodes.copy().items():
            target = item_id(key, namespace, ids, paired) if key[0] != "f" else None
            parts = ordered.get(target) if target else None
            if parts is not None and not original.refs and not original.choices:
                if sorted(parts) != sorted(original.parts):
                    raise InputError(f"Registry and tick traces disagree on {key}")
                nodes[key] = Node(parts, original.chance)

    @cache
    def bounds(node: Node, visiting: tuple[str, ...]) -> tuple[tuple[tuple[str, int], ...], int]:
        count = sum(amount for _, amount in node.parts)
        if node.choices:
            outcomes = [bounds(branch, visiting) for branch in node.choices]
            return outcomes[0][0], max(ceiling for _, ceiling in outcomes)
        representative = dict()  # Bundle repeated products in the microscope preview only.
        for item, amount in node.parts:
            representative[item] = representative.get(item, 0) + amount
        for key, weight in node.refs:
            if key not in nodes or key in visiting:
                raise InputError(f"Missing or cyclic material reference {key} via {visiting}")
            preview, ceiling = bounds(nodes[key], visiting + (key,))
            count += ceiling * math.ceil(weight)
            for item, amount in preview:
                representative[item] = representative.get(item, 0) + amount * math.ceil(weight)
        if count > 4096 or len(visiting) > 16:
            raise InputError(f"Minechem decomposition exceeds the bounded material graph: {visiting}")
        return tuple(representative.items()), count

    native = []
    seen = set()
    for key, node in nodes.items():
        preview, maximum = bounds(node, (key,))
        if key[0] == "f":
            name, volume = key[1:].split(":")
            id_ = native_fluids.get(key)
            if key in ({"fwater:1000", "flava:250"} if prepaid else {"fwater:144"}):
                id_ = f"minecraft:{name}"
            millibuckets = int(volume)
        else:
            id_, millibuckets = item_id(key, namespace, ids, paired), 0
        if not id_ or not maximum:
            continue
        if (id_, bool(millibuckets)) in seen:
            raise InputError(f"Flattening created a duplicate {prefix} material rule for {id_}")
        seen.add((id_, bool(millibuckets)))
        native.append(Material(key, id_, millibuckets, preview))
    return native, nodes

def synthesis394(namespace: str, ids: dict[str, str]):
    """Registered .394 recipes (distinct output IDs may share a ghost pattern)."""
    for key, row in observations("release394-synthesis-registry.txt").items():
        count, cost, shape, pattern = row.split(";", 3)
        parts = []
        for token in pattern.split(","):
            if token == "_":
                parts.append(None)
                continue
            part = PRODUCT.fullmatch(token)
            if not part:
                raise InputError(f"Invalid .394 synthesis pattern: {key}={row}")
            parts.append((chemical_id(part[1], ids), int(part[2])))
        count, cost = int(count), int(cost)
        if shape not in {"shaped", "shapeless"} or not 1 <= count <= 64 or not 1 <= cost <= 100_000 or not 1 <= len(parts) <= 9 or not any(parts):
            raise InputError(f"Invalid .394 synthesis row {key}={row}")
        output = item_id(key, namespace, ids)
        if not output or any(part and part[1] > 9 * 64 for part in parts):
            continue
        yield output, count, cost, shape == "shaped", tuple(parts)
