"""Version-scoped identities for vanilla objects in the literal-recipe subset.

The SRG and client-obfuscated aliases below are scoped by owner *and* descriptor.
They are not a general MCP mapping, numeric-ID remapper, or damage converter.
"""

from dataclasses import dataclass

from .classfiles import Symbol
from .inputs import InputError


# Physical item IDs in pre-registry Minecraft, not Item constructor offsets.
# Only entries whose legacy variants and modern stack limits are known are listed.
_IDS = {
    35: "wool", 264: "diamond", 265: "iron_ingot", 266: "gold_ingot", 287: "string",
    329: "saddle", 334: "leather", 339: "paper", 367: "rotten_flesh",
    417: "iron_horse_armor", 418: "golden_horse_armor", 419: "diamond_horse_armor", 421: "name_tag",
}
_WOOL = (
    "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
    "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black",
)
SINGLE_STACK = {"saddle", "iron_horse_armor", "golden_horse_armor", "diamond_horse_armor"}
# The old furnace stores XP by output item, not by recipe. Stay within items not used
# by any vanilla furnace recipe on either side until other interactions are modeled.
SMELT_ITEMS = {"leather", "rotten_flesh", "paper", "string"}

_ITEM = "net/minecraft/item/Item"
_BLOCK = "net/minecraft/block/Block"
_STACK = "net/minecraft/item/ItemStack"
_RECIPE = "net/minecraft/item/crafting/IRecipe"
_ITEMS = "net/minecraft/init/Items"
_BLOCKS = "net/minecraft/init/Blocks"
_FAMILIES = {
    "1.5.2": {"item": "wk", "block": "apa", "stack": "wm", "recipe": "ym"},
    "1.6.4": {"item": "yc", "block": "aqz", "stack": "ye", "recipe": "aah"},
    "1.7.10": {"item": "adb", "block": "aji", "stack": "add", "recipe": "afg", "items": "ade", "blocks": "ajn"},
}
_TYPES = {"item": _ITEM, "block": _BLOCK, "stack": _STACK, "recipe": _RECIPE, "items": _ITEMS, "blocks": _BLOCKS}

# Legacy SRG field, respective 1.5.2/1.6.4 obfuscated fields, and 1.7.10 SRG/raw.
# A missing raw entry in a release also excludes that field from that release.
_FIELDS = {
    "diamond": ("field_77702_n", "o", "p", "field_151045_i", "i"),
    "iron_ingot": ("field_77703_o", "p", "q", "field_151042_j", "j"),
    "gold_ingot": ("field_77717_p", "q", "r", "field_151043_k", "k"),
    "string": ("field_77683_K", "L", "M", "field_151007_F", "F"),
    "saddle": ("field_77765_aA", "aB", "aC", "field_151141_av", "av"),
    "leather": ("field_77770_aF", "aG", "aH", "field_151116_aA", "aA"),
    "paper": ("field_77759_aK", "aL", "aM", "field_151121_aF", "aF"),
    "rotten_flesh": ("field_77737_bm", "bn", "bo", "field_151078_bh", "bh"),
    "iron_horse_armor": ("field_111215_ce", None, "ce", "field_151138_bX", "bX"),
    "golden_horse_armor": ("field_111216_cf", None, "cf", "field_151136_bY", "bY"),
    "diamond_horse_armor": ("field_111213_cg", None, "cg", "field_151125_bZ", "bZ"),
    "name_tag": ("field_111212_ci", None, "ci", "field_151057_cb", "cb"),
    "wool": ("field_72101_ab", "af", "ag", "field_150325_L", "L"),
}


@dataclass(frozen=True)
class VanillaItem:
    name: str

    def ids(self, damage: int, *, ingredient: bool) -> tuple[str, ...]:
        if self.name == "wool":
            colors = _WOOL if damage == 32767 and ingredient else _WOOL[damage:damage + 1] if damage >= 0 else ()
            if colors:
                return tuple(f"minecraft:{color}_wool" for color in colors)
        elif damage == 0 or (damage == 32767 and ingredient):
            return (f"minecraft:{self.name}",)
        raise InputError(f"cannot preserve vanilla {self.name} damage/variant {damage}")


class VanillaNames:
    def __init__(self, version: str):
        self.version = version
        self.raw = _FAMILIES[version]
        self.by_type = {owner: kind for kind, owner in _TYPES.items()} | {owner: kind for kind, owner in self.raw.items()}
        if version != "1.7.10":
            self.by_type.update({"net/minecraft/src/" + _TYPES[kind].rsplit("/", 1)[-1]: kind for kind in self.raw})

    def type(self, owner: str) -> str | None:
        return self.by_type.get(owner)

    def descriptor(self, kind: str) -> tuple[str, ...]:
        return tuple(f"L{owner};" for owner, value in self.by_type.items() if value == kind)

    def item(self, field: Symbol) -> VanillaItem:
        modern = self.version == "1.7.10"
        raw_index = 4 if modern else 1 if self.version == "1.5.2" else 2
        for key, names in _FIELDS.items():
            raw = names[raw_index]
            if raw is None:
                continue
            value_type = "block" if key == "wool" else "item"
            owner_type = value_type + "s" if modern else value_type
            if (field == Symbol(self.raw[owner_type], raw, f"L{self.raw[value_type]};") or
                    (self.type(field.owner) == owner_type and field.owner != self.raw[owner_type] and
                     field.name == names[3 if modern else 0] and field.descriptor == f"L{_TYPES[value_type]};") or
                    (not modern and field == Symbol(f"net/minecraft/src/{_TYPES[value_type].rsplit('/', 1)[-1]}", names[0],
                                                    f"Lnet/minecraft/src/{_TYPES[value_type].rsplit('/', 1)[-1]};"))):
                return VanillaItem(key)
        raise InputError(f"no verified vanilla item mapping for {field.key} in {self.version}")

    def numeric(self, value: int) -> VanillaItem:
        name = _IDS.get(value)
        if self.version == "1.7.10" or name is None or _FIELDS[name][1 if self.version == "1.5.2" else 2] is None:
            raise InputError(f"no verified numeric item ID {value} in {self.version}")
        return VanillaItem(name)

    def item_id(self, field: Symbol, item: VanillaItem) -> int:
        # Used for getfield Item.itemID / Block.blockID on a known vanilla object.
        kind = "block" if item.name == "wool" else "item"
        obf = {"1.5.2": ("cp", "cz"), "1.6.4": ("cv", "cF")}
        if self.version in obf and field.descriptor == "I" and self.type(field.owner) == kind:
            raw = field.owner == self.raw[kind]
            expected = (obf[self.version][kind == "block"] if raw else
                        "field_71990_ca" if kind == "block" else "field_77779_bT")
            if field.name == expected:
                return next(value for value, name in _IDS.items() if name == item.name)
        raise InputError(f"unsupported field read {field.key}")
