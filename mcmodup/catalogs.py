"""Recover known static catalog declarations from particular legacy mod families."""

from collections import Counter
from dataclasses import dataclass
import re

from .classfiles import ClassFile, Instruction, Symbol
from .inputs import InputError


ELEMENT = "minechem/item/element/ElementEnum"
CATEGORY = "minechem/item/element/ElementClassificationEnum"
RADIATION = "minechem/radiation/RadiationEnum"
ROOM_STATE = "minechem/item/ChemicalRoomStateEnum"
RELEASE_394_SHA256 = "ea802167b760d43828a0648de3727a58d5c1f7737613123ae49f2743188cdae7"
RELEASE_394_PROFILE = "minechem-chemistry-394"
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
    source_sha256: str | None = None


PROFILES = {profile.name: profile for profile in (
    Profile("minechem-chemistry", "minechem", "1.6.4", (ELEMENT, MOLECULE), "minechem_chemistry", "Minechem Chemistry (Partial Port)",
            "CC-BY-SA-3.0", "Minechem and its contributors — https://github.com/iopleke/Minechem/tree/1.6.4-5.0.5-FINAL",
            "Most material recipes, original microscope and journal graphics, the rest of the machines "
            "and lens chain, reactors, fluids other than plain water in the exact FINAL profile, radiation effects, legacy power integrations, and old saves."),
    Profile(RELEASE_394_PROFILE, "minechem", "1.7.10", (ELEMENT, MOLECULE), "minechem_chemistry_394",
            "Minechem .394 Chemistry (Partial Port)", "Don't Be a Jerk (DBaJ) non-commercial license",
            "Minechem and its contributors — https://github.com/iopleke/Minechem/tree/1.7.10-5.0.5.394",
            "Unmapped materials, chemical and optional integration fluids, radiation, full Polytool mechanics, original menus and models, "
            "some configuration, other machines, legacy integrations and old saves.", RELEASE_394_SHA256),
    Profile("thaumcraft-aspects", "thaumcraft", "1.7.10", (ASPECT,), "thaumcraft_aspects", "Thaumcraft Aspects (Partial Port)",
            "Thaumcraft custom license — private use only unless authorized", "Thaumcraft by Azanor — https://www.curseforge.com/minecraft/mc-mods/thaumcraft",
            "Essentia storage and transport, research, wands, infusion, golems, magic, add-on integration, worldgen, and old saves."),
)}


def _definitions(info: ClassFile, *, enum: bool, factory: bool = False) -> dict[str, tuple[Instruction, ...]]:
    owner_type = f"L{info.owner};"
    mask = 0x4008 if enum else 0x0019  # static enum constants or public static final fields
    fields = {name for (name, descriptor), access in info.members.items() if descriptor == owner_type and access & mask == mask}
    if not fields or len(fields) > 512 or not info.initializer:
        raise InputError(f"No supported catalog fields and initializer in {info.owner}.")
    definitions = {}
    start = 0 if factory else None
    for i, op in enumerate(info.initializer):
        if not factory and op == Instruction("new", info.owner):
            start = i
        if op.op != "putstatic" or not isinstance(op.value, Symbol) or op.value.owner != info.owner:
            continue
        name = op.value.name
        if name in fields:
            if start is None or name in definitions or op.value.descriptor != owner_type:
                raise InputError(f"Can't recover a unique literal initialization of {info.owner}#{name}.")
            definitions[name] = info.initializer[start:i]
            if not factory:
                start = None
        if factory:
            # The .394 constants are straight-line assignments of literal
            # arguments to a known static factory, not JVM enum allocations.
            start = i + 1
    if set(definitions) != fields:
        raise InputError(f"Some catalog fields in {info.owner} have no supported initialization: {', '.join(sorted(fields - definitions)[:5])}.")
    return definitions


def _literal_field(value: object, owner: str) -> bool:
    return isinstance(value, Symbol) and value.owner == owner and value.descriptor == f"L{owner};"


def _elements(info: ClassFile, *, factory: bool = False) -> list[CatalogEntry]:
    state_type = ROOM_STATE if factory else CATEGORY
    properties = f"L{CATEGORY};L{state_type};L{RADIATION};"
    ctor = (Symbol(ELEMENT, "addElement", f"(I{TEXT}{TEXT}{properties})L{ELEMENT};") if factory else
            Symbol(ELEMENT, "<init>", f"({TEXT}I{TEXT}{properties})V"))
    entries = []
    for field, ops in _definitions(info, enum=not factory, factory=factory).items():
        prefix, call = ([], "static") if factory else (["new", "dup"], "constructor")
        if ([op.op for op in ops] != prefix + ["const", "const", "const", "getstatic", "getstatic", "getstatic", call]
                or ops[-1].value != ctor):
            raise InputError(f"Unsupported Minechem element initializer: {field}.")
        first, second, label, category, state, radiation = (op.value for op in ops[len(prefix):-1])
        symbol, metadata = (second, first) if factory else (first, second)
        if not (isinstance(symbol, str) and re.fullmatch(r"[A-Z][a-z]{0,3}", symbol) and symbol == field
                and type(metadata) is int and metadata == len(entries) + factory and isinstance(label, str) and 0 < len(label) <= 100):
            raise InputError(f"Invalid Minechem element name or legacy index for {field}.")
        for constant, owner in ((category, CATEGORY), (state, state_type), (radiation, RADIATION)):
            if not _literal_field(constant, owner):
                raise InputError(f"Unsupported Minechem property reference for {field}.")
        assert isinstance(category, Symbol) and isinstance(state, Symbol) and isinstance(radiation, Symbol)
        atomic_number = len(entries) + 1
        entries.append(CatalogEntry(
            f"element_{symbol.lower()}", label, "assets/minechem/textures/items/filledTestTube.png",
            (f"{symbol} · Atomic number {atomic_number}", f"{category.name} · {state.name}", f"Legacy radiation class: {radiation.name}"),
            {"symbol": symbol, "atomic_number": atomic_number, "legacy_metadata": metadata, "name": label,
             "category": category.name, "state": state.name, "radiation_class": radiation.name},
        ))
    return entries


def _molecules(info: ClassFile, elements: list[CatalogEntry], *, factory: bool = False) -> list[CatalogEntry]:
    refs = {(ELEMENT, entry.legacy["symbol"]): entry.path for entry in elements}
    entries = []
    paths, metadata_seen = set(), set()
    args = f"{TEXT}I" if factory else f"{TEXT}II"
    tail = f"L{ROOM_STATE};[L{CHEMICAL};)L{MOLECULE};" if factory else f"[L{CHEMICAL};)V"
    for field, ops in _definitions(info, enum=not factory, factory=factory).items():
        failure = f"Unsupported Minechem molecule declaration: {field}."
        if (not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]{0,63}", field) or len(ops) < (6 if factory else 8)
                or ops[-1].op != ("static" if factory else "constructor") or not isinstance(ops[-1].value, Symbol)):
            raise InputError(failure)
        metadata = len(entries)
        if factory:
            metadata = ops[1].value
            if (ops[0] != Instruction("const", field) or ops[1].op != "const" or type(metadata) is not int
                    or not 0 <= metadata <= 4095 or metadata in metadata_seen):
                raise InputError(failure)
        elif ops[1:5] != (Instruction("dup"), Instruction("const", field), Instruction("const", metadata), Instruction("const", metadata)):
            raise InputError(failure)
        ctor = ops[-1].value
        channels = 6 if ctor.descriptor == f"({args}FFFFFF{tail}" else 0
        if ctor != Symbol(MOLECULE, "addMolecule" if factory else "<init>", f"({args}{'F' * channels}{tail}"):
            raise InputError(failure)
        start = 2 if factory else 5
        colors = ops[start:start + channels]
        if len(colors) != channels or any(op.op != "float" or not 0.0 <= op.value <= 1.0 for op in colors):
            raise InputError(failure)
        cursor = start + channels
        properties = {}
        if factory:
            if cursor >= len(ops) - 1:
                raise InputError(failure)
            room = ops[cursor]
            if room.op != "getstatic" or not _literal_field(room.value, ROOM_STATE):
                raise InputError(failure)
            properties["state"] = room.value.name
            cursor += 1
        array = ops[cursor:-1]
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
        metadata_seen.add(metadata)
        refs[MOLECULE, field] = path
        name = re.sub(r"(?<=[a-z0-9])([A-Z])", r" \1", field).replace("_", " ").title()
        details = tuple(f"{qty} × {item.partition('_')[2]}" for item, qty in composition.items())
        entries.append(CatalogEntry(
            path, name, "assets/minechem/textures/items/filledMolecule.png", details,
            {"legacy_field": field, "legacy_metadata": metadata, "render_colors": [op.value for op in colors] or None, **properties},
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
    if profile.name in {"minechem-chemistry", RELEASE_394_PROFILE}:
        factory = profile.name == RELEASE_394_PROFILE
        elements = _elements(classes[ELEMENT], factory=factory)
        return elements + _molecules(classes[MOLECULE], elements, factory=factory)
    if profile.name == "thaumcraft-aspects":
        return _aspects(classes[ASPECT])
    raise InputError(f"No catalog importer for {profile.name!r}.")
