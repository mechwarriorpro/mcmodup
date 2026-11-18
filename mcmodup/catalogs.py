"""Recover known static catalog declarations from particular legacy mod families."""

from collections import Counter
from dataclasses import dataclass
import re

from .classfiles import ClassFile, Instruction, Symbol
from .inputs import InputError


ELEMENT = "minechem/item/element/ElementEnum"
CATEGORY = "minechem/item/element/ElementClassificationEnum"
RADIATION = "minechem/radiation/RadiationEnum"
MOLECULE = "minechem/item/molecule/MoleculeEnum"
CHEMICAL = "minechem/potion/PotionChemical"
CHEMICAL_OBJECTS = {"minechem/item/element/Element": ELEMENT, "minechem/item/molecule/Molecule": MOLECULE}
ASPECT = "thaumcraft/api/aspects/Aspect"
TEXT = "Ljava/lang/String;"


@dataclass(frozen=True)
class CatalogEntry:
    path: str
    label: str
    texture: str
    details: tuple[str, ...]
    legacy: dict
    components: tuple[tuple[str, int], ...] = ()  # (new item path, count)


@dataclass(frozen=True)
class Profile:
    name: str
    mod_id: str
    version: str
    owners: tuple[str, ...]
    namespace: str
    title: str
    license: str
    attribution: str
    not_ported: str


PROFILES = {profile.name: profile for profile in (
    Profile("minechem-chemistry", "minechem", "1.6.4", (ELEMENT, MOLECULE), "minechem_chemistry", "Minechem Chemistry (Partial Port)",
            "CC-BY-SA-3.0", "Minechem and its contributors — https://github.com/iopleke/Minechem/tree/1.6.4-5.0.5-FINAL",
            "Most material recipes, the rest of the machines, the chemist's journal, reactors, fluids, "
            "radiation effects, legacy power integrations, and old saves."),
    Profile("thaumcraft-aspects", "thaumcraft", "1.7.10", (ASPECT,), "thaumcraft_aspects", "Thaumcraft Aspects (Partial Port)",
            "Thaumcraft custom license — private use only unless authorized", "Thaumcraft by Azanor — https://www.curseforge.com/minecraft/mc-mods/thaumcraft",
            "Essentia storage and transport, research, wands, infusion, golems, magic, add-on integration, worldgen, and old saves."),
)}


def _definitions(info: ClassFile, *, enum: bool) -> dict[str, tuple[Instruction, ...]]:
    owner_type = f"L{info.owner};"
    mask = 0x4008 if enum else 0x0019  # static enum constants or public static final Aspect fields
    fields = {name for (name, descriptor), access in info.members.items() if descriptor == owner_type and access & mask == mask}
    if not fields or len(fields) > 512 or not info.initializer:
        raise InputError(f"No supported catalog fields and initializer in {info.owner}.")
    definitions = {}
    start = None
    for i, op in enumerate(info.initializer):
        if op == Instruction("new", info.owner):
            start = i
        if op.op == "putstatic" and isinstance(op.value, Symbol) and op.value.owner == info.owner and op.value.name in fields:
            name = op.value.name
            if start is None or name in definitions or op.value.descriptor != owner_type:
                raise InputError(f"Can't recover a unique literal initialization of {info.owner}#{name}.")
            definitions[name] = info.initializer[start:i]
            start = None
    if set(definitions) != fields:
        raise InputError(f"Some catalog fields in {info.owner} have no supported initialization: {', '.join(sorted(fields - definitions)[:5])}.")
    return definitions


def _elements(info: ClassFile) -> list[CatalogEntry]:
    ctor = Symbol(ELEMENT, "<init>", f"({TEXT}I{TEXT}L{CATEGORY};L{CATEGORY};L{RADIATION};)V")
    entries = []
    for field, ops in _definitions(info, enum=True).items():
        if ([op.op for op in ops] != ["new", "dup", "const", "const", "const", "getstatic", "getstatic", "getstatic", "constructor"]
                or ops[-1].value != ctor):
            raise InputError(f"Unsupported Minechem element initializer: {field}.")
        symbol, ordinal, label, category, state, radiation = (op.value for op in ops[2:-1])
        if not (isinstance(symbol, str) and re.fullmatch(r"[A-Z][a-z]{0,3}", symbol) and symbol == field
                and isinstance(ordinal, int) and ordinal == len(entries) and isinstance(label, str) and 0 < len(label) <= 100):
            raise InputError(f"Invalid Minechem element name or legacy index for {field}.")
        for constant, owner in ((category, CATEGORY), (state, CATEGORY), (radiation, RADIATION)):
            if not (isinstance(constant, Symbol) and constant.owner == owner and constant.descriptor == f"L{owner};"):
                raise InputError(f"Unsupported Minechem property reference for {field}.")
        assert isinstance(category, Symbol) and isinstance(state, Symbol) and isinstance(radiation, Symbol)
        entries.append(CatalogEntry(
            f"element_{symbol.lower()}", label, "assets/minechem/textures/items/filledTestTube.png",
            (f"{symbol} · Atomic number {ordinal + 1}", f"{category.name} · {state.name}", f"Legacy radiation class: {radiation.name}"),
            {"symbol": symbol, "atomic_number": ordinal + 1, "legacy_metadata": ordinal, "name": label,
             "category": category.name, "state": state.name, "radiation_class": radiation.name},
        ))
    return entries


def _molecules(info: ClassFile, elements: list[CatalogEntry]) -> list[CatalogEntry]:
    refs = {(ELEMENT, entry.legacy["symbol"]): entry.path for entry in elements}
    entries = []
    paths = set()
    for field, ops in _definitions(info, enum=True).items():
        failure = f"Unsupported Minechem molecule declaration: {field}."
        if (not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]{0,63}", field) or len(ops) < 8
                or ops[1:5] != (Instruction("dup"), Instruction("const", field),
                                Instruction("const", len(entries)), Instruction("const", len(entries)))
                or ops[-1].op != "constructor" or not isinstance(ops[-1].value, Symbol)):
            raise InputError(failure)
        ctor = ops[-1].value
        channels = 6 if ctor.descriptor == f"({TEXT}IIFFFFFF[L{CHEMICAL};)V" else 0
        if ctor != Symbol(MOLECULE, "<init>", f"({TEXT}II{'F' * channels}[L{CHEMICAL};)V"):
            raise InputError(failure)
        colors = ops[5:5 + channels]
        if len(colors) != channels or any(op.op != "float" or not 0.0 <= op.value <= 1.0 for op in colors):
            raise InputError(failure)
        array = ops[5 + channels:-1]
        if (len(array) < 2 or array[0].op != "const" or not isinstance(array[0].value, int)
                or not 1 <= array[0].value <= 128 or array[1] != Instruction("array", CHEMICAL)):
            raise InputError(failure)
        cursor = 2
        composition: Counter[str] = Counter()
        for index in range(array[0].value):
            part = array[cursor:cursor + 8]
            if len(part) < 7 or part[:2] != (Instruction("dup"), Instruction("const", index)):
                raise InputError(failure)
            obj, duplicate, static = part[2:5]
            ref = static.value
            if (obj.op != "new" or obj.value not in CHEMICAL_OBJECTS or duplicate.op != "dup"
                    or static.op != "getstatic" or not isinstance(ref, Symbol) or ref.owner != CHEMICAL_OBJECTS[obj.value]
                    or ref.descriptor != f"L{ref.owner};" or (ref.owner, ref.name) not in refs):
                raise InputError(failure)
            has_count = part[5].op == "const"
            count = part[5].value if has_count else 1
            call, store = 5 + has_count, 6 + has_count
            if (not isinstance(count, int) or not 1 <= count <= 64 or len(part) <= store
                    or part[call] != Instruction("constructor", Symbol(obj.value, "<init>", f"(L{ref.owner};{'I' if has_count else ''})V"))
                    or part[store].op != "aastore"):
                raise InputError(failure)
            composition[refs[ref.owner, ref.name]] += count
            cursor += store + 1
        path = f"molecule_{field.lower()}"
        if cursor != len(array) or path in paths:
            raise InputError(failure)
        paths.add(path)
        refs[MOLECULE, field] = path
        name = re.sub(r"(?<=[a-z0-9])([A-Z])", r" \1", field).replace("_", " ").title()
        details = tuple(f"{qty} × {item.partition('_')[2]}" for item, qty in composition.items())
        entries.append(CatalogEntry(
            path, name, "assets/minechem/textures/items/filledMolecule.png", details,
            {"legacy_field": field, "legacy_metadata": len(entries), "render_colors": [op.value for op in colors] or None},
            tuple(composition.items()),
        ))
    return entries


def _aspects(info: ClassFile) -> list[CatalogEntry]:
    entries = {}
    seen_tags = set()
    owner_type = f"L{ASPECT};"
    for field, ops in _definitions(info, enum=False).items():
        if (len(ops) < 6 or [op.op for op in ops[:4]] != ["new", "dup", "const", "const"]
                or ops[-1].op != "constructor" or not isinstance(ops[-1].value, Symbol)):
            raise InputError(f"Unsupported Thaumcraft aspect initializer: {field}.")
        tag, color, ctor = ops[2].value, ops[3].value, ops[-1].value
        if (not isinstance(tag, str) or not re.fullmatch(r"[a-z][a-z0-9_]{0,47}", tag) or tag in seen_tags
                or not isinstance(color, int) or not 0 <= color <= 0xFFFFFF or ctor.owner != ASPECT or ctor.name != "<init>"):
            raise InputError(f"Invalid Thaumcraft aspect tag, color, or constructor for {field}.")
        payload = ops[4:-1]
        components = []
        ingredients: Counter[str] = Counter()
        chat_color = None
        if ctor.descriptor == f"({TEXT}I{TEXT}I)V" and len(payload) == 2:
            chat, blend_op = payload
            if chat.op != "const" or not isinstance(chat.value, str):
                raise InputError(f"Unsupported Thaumcraft chat color for {field}.")
            chat_color = chat.value
        elif ctor.descriptor in {f"({TEXT}I[{owner_type})V", f"({TEXT}I[{owner_type}I)V"}:
            has_blend = ctor.descriptor.endswith(f"{owner_type}I)V")
            if has_blend and not payload:
                raise InputError(f"Missing Thaumcraft blend for {field}.")
            blend_op = payload[-1] if has_blend else Instruction("const", 1)
            array = payload[:-1] if has_blend else payload
            if array != (Instruction("const"),):
                if len(array) != 10 or array[:2] != (Instruction("const", 2), Instruction("array", ASPECT)):
                    raise InputError(f"Unsupported Thaumcraft component array for {field}.")
                for n in range(2):
                    dup, index, component, store = array[2 + n * 4:6 + n * 4]
                    ref = component.value
                    if (dup.op != "dup" or index != Instruction("const", n) or store.op != "aastore" or component.op != "getstatic"
                            or not isinstance(ref, Symbol) or ref.owner != ASPECT or ref.descriptor != owner_type or ref.name not in entries):
                        raise InputError(f"Unresolved Thaumcraft aspect component for {field}.")
                    component = entries[ref.name]
                    components.append(component.legacy["tag"])
                    ingredients[component.path] += 1
        else:
            raise InputError(f"Unsupported Thaumcraft aspect constructor for {field}.")
        if blend_op.op != "const" or blend_op.value not in {1, 771}:
            raise InputError(f"Unsupported Thaumcraft blend value for {field}.")
        seen_tags.add(tag)
        entries[field] = CatalogEntry(
            f"aspect_{tag}", f"{tag.title()} Aspect", f"assets/thaumcraft/textures/aspects/{tag}.png",
            (f"Legacy RGB: #{color:06X}", f"Components: {', '.join(components)}" if components else "Primal aspect"),
            {"tag": tag, "legacy_field": field, "color": color, "components": components,
             "chat_color": chat_color, "blend": blend_op.value},
            tuple(ingredients.items()),
        )
    return list(entries.values())


def recover_catalog(profile: Profile, classes: dict[str, ClassFile]) -> list[CatalogEntry]:
    for owner in profile.owners:
        if owner not in classes or classes[owner].owner != owner:
            raise InputError(f"Expected catalog class {owner}.")
    if profile.name == "minechem-chemistry":
        elements = _elements(classes[ELEMENT])
        return elements + _molecules(classes[MOLECULE], elements)
    if profile.name == "thaumcraft-aspects":
        return _aspects(classes[ASPECT])
    raise InputError(f"No catalog importer for {profile.name!r}.")
