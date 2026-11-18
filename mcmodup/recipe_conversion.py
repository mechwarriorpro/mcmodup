"""Lower self-contained legacy recipe registrations to a NeoForge low-code mod.

This is a bounded static translation, not JVM emulation or a search for calls to
copy out of an otherwise unsupported mod. Every executable method must fit the
model; an unknown instruction, effect, ingredient, or resource blocks the JAR.
"""

from collections import Counter
from dataclasses import dataclass, field
from functools import cached_property
import hashlib
from io import BytesIO
import json
import math
from pathlib import Path
import re
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

from . import MINECRAFT_VERSION, SOURCE_VERSIONS
from .audit import ModMetadata, _read_metadata
from .classfiles import ClassFile, Instruction, LEGACY_MOD_ANNOTATION, Symbol, class_entries, read_class
from .inputs import InputError, open_input
from .port import _toml
from .recipe_symbols import SINGLE_STACK, SMELT_ITEMS, VanillaItem, VanillaNames
from .source_ports import MOD_ID, RESERVED, VERSION, digest


PROVENANCE = "META-INF/mcmodup/recipe-conversion.json"
MOD_METADATA = "META-INF/neoforge.mods.toml"
PROFILE = "literal-forge-recipes-v1"
MAX_ARCHIVE = 256 * 1024
MAX_CLASS = 96 * 1024
MAX_RECIPES = 64
_EVENT = "cpw/mods/fml/common/event/"
_PHASES = ("FMLPreInitializationEvent", "FMLInitializationEvent", "FMLPostInitializationEvent", "FMLServerStartingEvent")
_PHASE_INDEX = {f"(L{_EVENT}{event};)V": index for index, event in enumerate(_PHASES)}
_NETWORK = "Lcpw/mods/fml/common/network/NetworkMod;"
_INSTANCE = "Lcpw/mods/fml/common/Mod$Instance;"
_REGISTRY = "cpw/mods/fml/common/registry/GameRegistry"
_LOGGER = "Ljava/util/logging/Logger;"
_LOG_LEVELS = {"INFO", "WARNING", "SEVERE", "CONFIG", "FINE", "FINER", "FINEST"}
_RETURN = (Instruction("return"),)
_NEW_OBJECT = (Instruction("this"), Instruction("constructor", Symbol("java/lang/Object", "<init>", "()V")), *_RETURN)
_DOCUMENT = re.compile(r"(?:META-INF/)?(?:LICENSE|COPYING|NOTICE|README)(?:\.(?:txt|md))?", re.I)
_MANIFEST_FIELDS = {"manifest-version", "created-by", "built-by", "build-jdk", "implementation-title",
                    "implementation-version", "implementation-vendor", "specification-title", "specification-version",
                    "specification-vendor", "archiver-version"}


def _json(value: dict) -> bytes:
    return (json.dumps(value, ensure_ascii=True, indent=2) + "\n").encode()


def _ingredient(ids: tuple[str, ...]) -> dict | list[dict]:
    items = [{"item": name} for name in ids]
    return items[0] if len(items) == 1 else items


@dataclass(frozen=True)
class Recipe:
    kind: str
    result: str
    count: int
    ingredients: tuple[tuple[str, ...], ...] = ()
    pattern: tuple[str, ...] = ()
    key: tuple[tuple[str, tuple[str, ...]], ...] = ()
    legacy_experience: float | None = None

    def data(self) -> dict:
        result = {"type": "minecraft:" + self.kind, "result": {"id": self.result, "count": self.count}}
        if self.kind == "smelting":
            # SlotFurnace starts from items removed and only scales down if XP < 1.
            # Our smelting subset emits exactly one item per operation.
            result.update(ingredient=_ingredient(self.ingredients[0]), experience=min(1.0, self.legacy_experience), cookingtime=200)
        elif self.kind == "crafting_shaped":
            result.update(pattern=list(self.pattern), key={key: _ingredient(ids) for key, ids in self.key})
        else:
            result.update(ingredients=[_ingredient(ids) for ids in self.ingredients])
        return result


@dataclass(frozen=True)
class RecipeConversion:
    source_version: str
    source_sha256: str
    legacy_id: str
    mod_id: str
    version: str
    name: str
    description: str
    authors: str
    license: str
    recipes: tuple[Recipe, ...]
    legacy_network: tuple[tuple[str, bool], ...]
    ignored_log_calls: int
    documents: tuple[tuple[str, bytes], ...] = ()

    @property
    def filename(self) -> str:
        return f"{self.mod_id}-{self.version}-mcmodup-{MINECRAFT_VERSION}.jar"

    def recipe_id(self, index: int) -> str:
        return f"{self.mod_id}:legacy_{index + 1:03d}"

    def report(self) -> dict:
        return {"profile": PROFILE, "source_minecraft": self.source_version, "source_sha256": self.source_sha256,
                "source_mod_id": self.legacy_id, "recipes": dict(sorted(Counter(r.kind for r in self.recipes).items())),
                "legacy_network_flags": dict(self.legacy_network), "ignored_diagnostic_calls": self.ignored_log_calls,
                "verification": "static-literal-registrations"}

    def metadata(self) -> str:
        lines = ['modLoader="lowcodefml"', 'loaderVersion="[1,)"', f"license={_toml(self.license)}", "", "[[mods]]",
                 f"modId={_toml(self.mod_id)}", f"version={_toml(self.version)}", f"displayName={_toml(self.name)}",
                 f"description={_toml(self.description or 'Automatically converted legacy vanilla recipes.')}" ]
        if self.authors:
            lines.append(f"authors={_toml(self.authors)}")
        for dep, version in (("neoforge", "[21.1,21.2)"), ("minecraft", f"[{MINECRAFT_VERSION}]")):
            lines.extend(("", f"[[dependencies.{self.mod_id}]]", f'modId="{dep}"', 'type="required"',
                          f'versionRange="{version}"', 'ordering="NONE"', 'side="BOTH"'))
        return "\n".join(lines) + "\n"

    @cached_property
    def jar(self) -> bytes:
        files = {MOD_METADATA: self.metadata().encode(),
                 "pack.mcmeta": _json({"pack": {"description": self.name, "pack_format": 48, "supported_formats": [34, 48]}}),
                 PROVENANCE: _json(self.report()), **dict(self.documents)}
        for index, recipe in enumerate(self.recipes):
            namespace, key = self.recipe_id(index).split(":", 1)
            files[f"data/{namespace}/recipe/{key}.json"] = _json(recipe.data())
        buffer = BytesIO()
        with ZipFile(buffer, "w") as jar:
            for path, value in sorted(files.items()):
                entry = ZipInfo(path)  # No clock time or original ZIP attributes.
                entry.compress_type = ZIP_DEFLATED
                jar.writestr(entry, value)
        return buffer.getvalue()

    @cached_property
    def sha256(self) -> str:
        return hashlib.sha256(self.jar).hexdigest()


@dataclass(frozen=True)
class _Stack:
    item: VanillaItem
    count: int
    damage: int


@dataclass(frozen=True)
class _Char:
    value: str


@dataclass
class _New:
    owner: str
    stack: _Stack | None = None


@dataclass
class _Array:
    owner: str
    items: list


class _Recipes:
    """Typed abstract values and a closed list of supported registration effects."""

    def __init__(self, version: str):
        self.names = VanillaNames(version)
        self.recipes: list[Recipe] = []
        self.ignored_log_calls = 0
        self._logger_fields: set[Symbol] = set()
        self._inputs: set[tuple] = set()
        self._smelting_outputs: set[str] = set()

    @staticmethod
    def _as(value, kind):
        if type(value) is not kind:
            raise InputError(f"expected a literal {kind.__name__} in recipe bytecode")
        return value

    @staticmethod
    def _stack(value) -> _Stack:
        if type(value) is not _New or value.stack is None:
            raise InputError("expected a constructed literal ItemStack")
        return value.stack

    def _ingredient(self, value, *, shaped: bool = False) -> tuple[str, ...]:
        if isinstance(value, VanillaItem):
            # Only shaped Block arguments, not explicit ItemStacks, receive ANY metadata.
            return value.ids(32767 if shaped and value.name == "wool" else 0, ingredient=True)
        stack = self._stack(value)
        return stack.item.ids(stack.damage, ingredient=True)  # Legacy matching ignores stack count.

    def _add(self, recipe: Recipe, signature: tuple):
        if len(self.recipes) >= MAX_RECIPES or signature in self._inputs:
            raise InputError("too many recipes or ambiguous duplicate recipe inputs")
        self._inputs.add(signature)
        self.recipes.append(recipe)

    def _register(self, fn: Symbol, stack: list, phase: int, result_token: object) -> None:
        if phase >= 3:
            raise InputError("recipes registered after initial load are not supported")
        if fn.name == "addSmelting" and any(fn.descriptor == f"(I{item}F)V" for item in self.names.descriptor("stack")):
            xp = self._as(stack.pop(), float)
            output = self._stack(stack.pop())
            ingredient = self.names.numeric(self._as(stack.pop(), int))
            if (output.count != 1 or ingredient.name not in SMELT_ITEMS or output.item.name not in SMELT_ITEMS or
                    not math.isfinite(xp) or xp < 0):
                raise InputError("furnace input, output count, or XP is outside the verified vanilla smelting subset")
            result, = output.item.ids(output.damage, ingredient=False)
            if result in self._smelting_outputs:
                raise InputError("multiple smelting registrations share the legacy output XP slot")
            self._smelting_outputs.add(result)
            inputs = ingredient.ids(0, ingredient=True)
            self._add(Recipe("smelting", result, 1, (inputs,), legacy_experience=xp), ("smelting", inputs))
            return
        kind = {"addRecipe": "crafting_shaped", "addShapedRecipe": "crafting_shaped", "addShapelessRecipe": "crafting_shapeless"}.get(fn.name)
        if kind is None:
            raise InputError(f"unsupported legacy registration {fn.key}")
        returns = self.names.descriptor("recipe") if fn.name == "addShapedRecipe" else ("V",)
        if not any(fn.descriptor == f"({item}[Ljava/lang/Object;){ret}" for item in self.names.descriptor("stack") for ret in returns):
            raise InputError(f"unsupported legacy registration descriptor {fn.key}")
        array = self._as(stack.pop(), _Array)
        if array.owner != "java/lang/Object" or any(value is None for value in array.items):
            raise InputError("recipe arguments must be a filled literal Object array")
        output = self._stack(stack.pop())
        result, = output.item.ids(output.damage, ingredient=False)
        if not 1 <= output.count <= (1 if output.item.name in SINGLE_STACK else 64):
            raise InputError("result count exceeds the supported modern stack size")
        if kind == "crafting_shapeless":
            if not 1 <= len(array.items) <= 9:
                raise InputError("expected 1 to 9 shapeless ingredients")
            ingredients = tuple(self._ingredient(value) for value in array.items)
            self._add(Recipe(kind, result, output.count, ingredients), (kind, *sorted(ingredients)))
            return
        args = array.items
        if args and isinstance(args[0], _Array) and args[0].owner == "java/lang/String":
            pattern, args = tuple(args[0].items), args[1:]
        else:
            size = 0
            while size < len(args) and isinstance(args[size], str):
                size += 1
            pattern, args = tuple(args[:size]), args[size:]
        if (not 1 <= len(pattern) <= 3 or any(not isinstance(row, str) or not row.strip() for row in pattern) or
                not 1 <= len(pattern[0]) <= 3 or any(len(row) != len(pattern[0]) for row in pattern) or
                any(all(c == " " for c in col) for col in (tuple(row[0] for row in pattern), tuple(row[-1] for row in pattern)))):
            raise InputError("unsupported shaped pattern; blank outer rows or columns change matching on 1.21.1")
        key = {}
        if not args or len(args) % 2:
            raise InputError("expected shaped ingredient key pairs")
        for char, value in zip(args[::2], args[1::2]):
            char = self._as(char, _Char).value
            if char in key or not char.isascii() or not char.isalnum():
                raise InputError("invalid or duplicate shaped ingredient key")
            key[char] = self._ingredient(value, shaped=True)
        if set("".join(pattern)) - {" "} != key.keys():
            raise InputError("shaped recipe has incomplete or unused ingredient keys")
        grid = tuple(tuple(key.get(char, ()) for char in row) for row in pattern)
        mirrored = tuple(tuple(reversed(row)) for row in grid)
        self._add(Recipe(kind, result, output.count, pattern=pattern, key=tuple(sorted(key.items()))), (kind, min(grid, mirrored)))
        if not fn.descriptor.endswith("V"):
            stack.append(result_token)

    def read(self, cls: ClassFile, code: tuple[Instruction, ...], phase: int, logger_fields: set[Symbol]) -> None:
        stack = []
        event, logger, sysout, loglevel, recipe_result = (object() for _ in range(5))
        try:
            for ins in code:
                op, v = ins.op, ins.value
                if op in {"const", "float"}:
                    stack.append(v)
                elif op == "argument":
                    stack.append(event)
                elif op == "new" and self.names.type(v) == "stack":
                    stack.append(_New(v))
                elif op == "dup":
                    stack.append(stack[-1])
                elif op == "array" and v in {"java/lang/Object", "java/lang/String"}:
                    size = self._as(stack.pop(), int)
                    if not 1 <= size <= 24:
                        raise InputError("invalid recipe array size")
                    stack.append(_Array(v, [None] * size))
                elif op == "aastore":
                    value, index, array = stack.pop(), self._as(stack.pop(), int), self._as(stack.pop(), _Array)
                    valid = isinstance(value, str) if array.owner == "java/lang/String" else isinstance(value, (str, VanillaItem, _New, _Char, _Array))
                    if not valid or value is array or not 0 <= index < len(array.items) or array.items[index] is not None:
                        raise InputError("unsupported recipe array assignment")
                    array.items[index] = value
                elif op == "getstatic":
                    if v == Symbol("java/lang/System", "out", "Ljava/io/PrintStream;"):
                        stack.append(sysout)
                    elif v in self._logger_fields:
                        stack.append(logger)
                    elif v.owner == "java/util/logging/Level" and v.descriptor == "Ljava/util/logging/Level;" and v.name in _LOG_LEVELS:
                        stack.append(loglevel)
                    else:
                        stack.append(self.names.item(v))
                elif op == "getfield":
                    stack.append(self.names.item_id(v, self._as(stack.pop(), VanillaItem)))
                elif op == "putstatic" and v in logger_fields and v not in self._logger_fields and phase == 0:
                    if stack.pop() is not logger:
                        raise InputError("unexpected value stored in diagnostic logger field")
                    self._logger_fields.add(v)
                elif op == "constructor" and v.name == "<init>" and self.names.type(v.owner) == "stack":
                    match = re.fullmatch(r"\((L[^;]+;|I)(I{0,2})\)V", v.descriptor)
                    if not match or (match[1] == "I" and match[2] != "II"):
                        raise InputError(f"unsupported literal ItemStack constructor {v.descriptor}")
                    damage = self._as(stack.pop(), int) if len(match[2]) == 2 else 0
                    count = self._as(stack.pop(), int) if match[2] else 1
                    value = stack.pop()
                    if match[1] == "I":
                        item = self.names.numeric(self._as(value, int))
                    else:
                        item = self._as(value, VanillaItem)
                        if self.names.type(match[1][1:-1]) != ("block" if item.name == "wool" else "item"):
                            raise InputError("ItemStack constructor argument does not match the vanilla item type")
                    target = self._as(stack.pop(), _New)
                    if target.owner != v.owner or target.stack is not None:
                        raise InputError("invalid ItemStack initialization")
                    target.stack = _Stack(item, count, damage)
                elif op == "static" and v == Symbol("java/lang/Character", "valueOf", "(C)Ljava/lang/Character;"):
                    char = self._as(stack.pop(), int)
                    if not 0 <= char < 128:
                        raise InputError("non-ASCII shaped key")
                    stack.append(_Char(chr(char)))
                elif op == "static" and v.owner == _REGISTRY:
                    self._register(v, stack, phase, recipe_result)
                elif op == "pop" and stack.pop() is recipe_result:
                    pass
                elif op == "invoke" and v == Symbol(_EVENT + _PHASES[0], "getModLog", f"(){_LOGGER}") and phase == 0:
                    if stack.pop() is not event:
                        raise InputError("expected the FML pre-init event as logger receiver")
                    stack.append(logger)
                elif op == "invoke" and v == Symbol("java/io/PrintStream", "println", "(Ljava/lang/String;)V"):
                    self._as(stack.pop(), str)
                    if stack.pop() is not sysout:
                        raise InputError("expected a literal startup print")
                    self.ignored_log_calls += 1
                elif op == "invoke" and v == Symbol("java/util/logging/Logger", "log", "(Ljava/util/logging/Level;Ljava/lang/String;)V"):
                    self._as(stack.pop(), str)
                    if stack.pop() is not loglevel or stack.pop() is not logger:
                        raise InputError("expected a literal startup logger call")
                    self.ignored_log_calls += 1
                elif op == "return" and not stack:
                    continue
                else:
                    raise InputError(f"unsupported effect {op} {v.key if isinstance(v, Symbol) else (v or '')}")
        except IndexError as exc:
            raise InputError(f"invalid operand stack in {cls.owner}") from exc


def _lifecycle(cls: ClassFile, version: str, legacy_id: str) -> _Recipes:
    if cls.parents != ("java/lang/Object",) or cls.access not in {0x21, 0x31} or cls.code.get(("<init>", "()V")) != _NEW_OBJECT:
        raise InputError("expected one ordinary public FML mod class with an inert zero-argument constructor")
    evaluator = _Recipes(version)
    phases, instances, loggers = {}, set(), set()
    for key, flags in cls.members.items():
        name, descriptor = key
        annotations = cls.member_annotations.get(key, ())
        if key in {("<init>", "()V"), ("<clinit>", "()V")}:
            if annotations or flags != (1 if name == "<init>" else 8):
                raise InputError("unsupported recipe initializer modifiers or annotations")
            continue
        if key in cls.code and descriptor in _PHASE_INDEX:
            index = _PHASE_INDEX[descriptor]
            handler = "Lcpw/mods/fml/common/Mod$EventHandler;"
            legacy_handler = f"Lcpw/mods/fml/common/Mod${('PreInit', 'Init', 'PostInit', 'ServerStarting')[index]};"
            allowed = {handler, legacy_handler} if version == "1.5.2" else {handler}
            if (flags not in {1, 17} or len(annotations) != 1 or annotations[0].descriptor not in allowed or
                    annotations[0].values or index in phases):
                raise InputError(f"unsupported or ambiguous FML lifecycle method {name}{descriptor}")
            phases[index] = cls.code[key]
        elif not descriptor.startswith("(") and flags in {8, 9, 10, 12}:
            symbol = Symbol(cls.owner, name, descriptor)
            if (descriptor == f"L{cls.owner};" and len(annotations) == 1 and annotations[0].descriptor == _INSTANCE and
                    annotations[0].values in ({}, {"value": ""}, {"value": legacy_id})):
                instances.add(symbol)
            elif descriptor == _LOGGER and not annotations:
                loggers.add(symbol)
            else:
                raise InputError(f"unsupported mod field {symbol.key}")
        else:
            raise InputError(f"unsupported mod member {name}{descriptor}")
    init = cls.code.get(("<clinit>", "()V"), _RETURN)
    if init != _RETURN:
        prefix = (Instruction("new", cls.owner), Instruction("dup"), Instruction("constructor", Symbol(cls.owner, "<init>", "()V")))
        if len(init) != 5 or init[:3] != prefix or init[3].op != "putstatic" or init[3].value not in instances or init[-1:] != _RETURN:
            raise InputError("nontrivial static initialization outside the recipe subset")
    for phase, body in sorted(phases.items()):
        evaluator.read(cls, body, phase, loggers)
    if not evaluator.recipes:
        raise InputError("no supported literal recipe registrations")
    return evaluator


def analyze(path: Path, version: str) -> RecipeConversion:
    """Analyze an archive at an already established, exact installed Forge version."""
    if version not in SOURCE_VERSIONS or not path.is_file() or path.stat().st_size > MAX_ARCHIVE:
        raise InputError("requires a small original Forge archive from a supported exact Minecraft version")
    before = digest(path)
    with open_input(path) as original:
        files = original.files
        classes = class_entries(files)
        if original.warnings or len(files) > 16 or len(classes) != 1:
            raise InputError("only single-class recipe-only FML archives are supported")
        owner, entry = next(iter(classes.items()))
        docs = []
        for name in files:
            if name == "META-INF/MANIFEST.MF":
                for line in original.text(name).splitlines():
                    if line.strip() and line.partition(": ")[0].casefold() not in _MANIFEST_FIELDS:
                        raise InputError("unrecognized legacy manifest metadata or loader hook")
            elif _DOCUMENT.fullmatch(name):
                docs.append((name, original.read(name, 64 * 1024)))
            elif name not in {entry, "mcmod.info"}:
                raise InputError(f"unsupported resource or loader hook {name!r}")
        cls = read_class(original.read(entry, MAX_CLASS), collect_references=False, capture_program=True)
        if cls.owner != owner:
            raise InputError("class name does not match its archive path")
        annotations = {a.descriptor: a.values for a in cls.annotations}
        if (len(annotations) != len(cls.annotations) or LEGACY_MOD_ANNOTATION not in annotations or
                annotations.keys() - {LEGACY_MOD_ANNOTATION, _NETWORK} or (version == "1.7.10" and _NETWORK in annotations)):
            raise InputError("unsupported or ambiguous class annotations")
        values, network = annotations[LEGACY_MOD_ANNOTATION], annotations.get(_NETWORK, {})
        if (values.keys() - {"modid", "name", "version", "acceptedMinecraftVersions", "acceptableRemoteVersions", "dependencies"} or
                any(not isinstance(value, str) for value in values.values()) or values.get("dependencies", "").strip() or
                values.get("acceptedMinecraftVersions", "") not in {"", version, f"[{version}]"} or
                values.get("acceptableRemoteVersions", "") not in {"", "*"} or
                network.keys() - {"clientSideRequired", "serverSideRequired"} or any(type(value) is not bool for value in network.values())):
            raise InputError("unsupported FML dependency, loader option, or network handler")
        legacy_id = values.get("modid", "")
        mod_id = legacy_id.lower()
        release = values.get("version", "")
        if not MOD_ID.fullmatch(mod_id) or mod_id in RESERVED or not VERSION.fullmatch(release):
            raise InputError("legacy @Mod ID or version cannot be used in NeoForge metadata")
        meta = ModMetadata("")
        if "mcmod.info" in files:
            rows = _read_metadata(original.text("mcmod.info"), "mcmod.info")
            if (len(rows) != 1 or rows[0].mod_id.lower() != mod_id or rows[0].dependencies or
                    rows[0].minecraft not in {"", version} or rows[0].version not in {"", release}):
                raise InputError("legacy mcmod.info disagrees with the recipe mod's identity, release, or standalone scope")
            meta = rows[0]
        evaluator = _lifecycle(cls, version, legacy_id)
    if digest(path) != before:
        raise InputError("original archive changed during recipe analysis")
    return RecipeConversion(version, before, legacy_id, mod_id, release, values.get("name") or meta.name or mod_id,
                            meta.description, meta.authors, meta.license or "Original mod license applies (local conversion)",
                            tuple(evaluator.recipes), tuple(sorted(network.items())), evaluator.ignored_log_calls, tuple(docs))
