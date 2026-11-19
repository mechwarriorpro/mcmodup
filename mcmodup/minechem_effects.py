"""Translate frozen in-game pharmacology observations, not molecular formulas, to NeoForge."""
import json
import re
import struct
import zlib

from .inputs import InputError
from .minechem_materials import TEMPLATES, chemical_id, observations

JAVA = ("ChemistryEffects", "ChemicalTreatmentRecipe", "ChemistryEffectsClient")
# The original .394 config gates these registry additions.
RECREATIONAL_394 = {"m34", "m35", "m63", "m87", "m100", "m101", "m172"}
POTIONS = {1, 2, 3, 5, 8, 9, 10, 15, 16, 17, 18, 19, 20, 32}


def _action(token: str, prepaid: bool) -> tuple[str, int, int, int, float]:
    kind, fields = token[0], token[1:].split(":")
    if kind == "p" and len(fields) == 3:
        id_, ticks, amp = (int(value) for value in fields)
        ticks *= 20 if prepaid else 1
        if id_ in POTIONS and 0 < ticks <= 144_000 and 0 <= amp <= 20:
            return kind, id_, ticks, amp, 0
    elif kind == "f" and len(fields) == 2 and 0 < int(fields[0]) <= 20 and 0 <= float(fields[1]) <= 1:
        return kind, int(fields[0]), 0, 0, round(float(fields[1]), 6)
    elif kind == "c" and fields == ["-1"]:
        return kind, 0, 0, 0, 0
    elif kind == "b" and len(fields) == 1 and 0 < int(fields[0]) <= 1000:
        return kind, int(fields[0]), 0, 0, 0
    elif kind == "d" and len(fields) == 1 and 0 < float(fields[0]) <= 20:
        return kind, 0, 0, 0, float(fields[0])
    raise InputError(f"Unsupported observation in Minechem pharmacology: {token}")


def entries(ids: dict[str, str], *, prepaid: bool):
    rows = observations("release394-effects.txt" if prepaid else "final-effects.txt")
    foods = rows.keys() if prepaid else observations("final-foods.txt").keys()
    if not set(foods) <= rows.keys() or (not prepaid and rows.keys() != {key for key in ids if key.startswith("m")}):
        raise InputError("The molecule catalog and pharmacology observations disagree")
    for key, row in rows.items():
        if not re.fullmatch(r"m\d{1,3}", key):
            raise InputError(f"Invalid observed molecule {key}")
        if prepaid:
            coated, data = True, row
        else:
            coat, data = row.split(";", 1)
            coated = coat == "yes"
        actions = [_action(token, prepaid) for token in data.split(",") if token]
        yield chemical_id(key, ids), coated, key in foods, prepaid and key in RECREATIONAL_394, actions


def java_rows(ids: dict[str, str], *, prepaid: bool) -> str:
    rows = []
    for id_, coat, food, recreational, actions in entries(ids, prepaid=prepaid):
        flags = ", ".join(str(value).lower() for value in (coat, food, recreational))
        code = ", ".join(f"new Action('{kind}', {a}, {b}, {c}, {amount}F)" for kind, a, b, c, amount in actions)
        rows.append(f"        Map.entry({json.dumps(id_)}, new Profile({flags}, List.of({code})))")
    return ",\n".join(rows)


def delirium_icon() -> bytes:
    """Build a small original symbol for the local MobEffect atlas (no borrowed vanilla sprite)."""
    def chunk(name: bytes, contents: bytes) -> bytes:
        return struct.pack(">I", len(contents)) + name + contents + struct.pack(">I", zlib.crc32(name + contents))
    rows = []
    for y in range(18):
        row = bytearray([0])
        for x in range(18):
            i, j = x - 8.5, y - 8.5
            if i * i + 2.5 * j * j <= 53:
                color = (40, 9, 63, 255) if i * i + j * j < 10 else (0, 255, 110, 255)
            else:
                color = (0, 0, 0, 0)
            row.extend(color)
        rows.append(row)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 18, 18, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(b"".join(rows))) + chunk(b"IEND", b""))
