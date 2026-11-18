"""Read JVM declarations and references without loading classes or running a JAR."""

from dataclasses import dataclass, field
import re
import struct

from .inputs import InputError


CLASS_LIMIT = 8 * 1024 * 1024
INTERNAL_NAME = r"[A-Za-z0-9_$-]+(?:/[A-Za-z0-9_$-]+)*"
CLASS_NAME = re.compile(INTERNAL_NAME)
TYPE_DESCRIPTOR = rf"\[*(?:[BCDFIJSZ]|L{INTERNAL_NAME};)"
FIELD_DESCRIPTOR = re.compile(TYPE_DESCRIPTOR)
METHOD_DESCRIPTOR = re.compile(rf"\((?:{TYPE_DESCRIPTOR})*\)(?:V|{TYPE_DESCRIPTOR})")
DESCRIPTOR_CLASSES = re.compile(rf"L({INTERNAL_NAME})(?=[;<])")
JVM_MEMBER = re.compile(r"[A-Za-z0-9_$-]+|<init>|<clinit>")
LEGACY_MOD_ANNOTATION = "Lcpw/mods/fml/common/Mod;"


@dataclass(frozen=True, order=True)
class Symbol:
    owner: str
    name: str = ""
    descriptor: str = ""

    @property
    def kind(self) -> str:
        if not self.name:
            return "class"
        return "method" if self.descriptor.startswith("(") else "field"

    @property
    def key(self) -> str:
        if not self.name:
            return self.owner
        return self.owner + "#" + self.name + (":" if self.kind == "field" else "") + self.descriptor

    @classmethod
    def parse(cls, text: str) -> "Symbol":
        owner, sep, part = text.partition("#")
        if CLASS_NAME.fullmatch(owner):
            if not sep:
                return cls(owner)
            name, delimiter, descriptor = part.partition(":")
            if delimiter and JVM_MEMBER.fullmatch(name) and FIELD_DESCRIPTOR.fullmatch(descriptor):
                return cls(owner, name, descriptor)
            name, delimiter, rest = part.partition("(")
            if delimiter and JVM_MEMBER.fullmatch(name) and METHOD_DESCRIPTOR.fullmatch("(" + rest):
                return cls(owner, name, "(" + rest)
        raise InputError(f"Invalid JVM symbol {text!r}; use owner, owner#method(args)return, or owner#field:type.")


@dataclass(frozen=True)
class Instruction:
    op: str
    value: str | int | float | Symbol | None = None


@dataclass(frozen=True)
class Annotation:
    descriptor: str
    values: dict[str, object]


@dataclass
class ClassFile:
    owner: str
    access: int
    parents: tuple[str, ...]
    members: dict[tuple[str, str], int]
    references: set[Symbol] = field(default_factory=set)
    legacy_mod: dict[str, str] | None = None
    initializer: tuple[Instruction, ...] | None = None
    annotations: tuple[Annotation, ...] = ()
    member_annotations: dict[tuple[str, str], tuple[Annotation, ...]] = field(default_factory=dict)
    code: dict[tuple[str, str], tuple[Instruction, ...]] = field(default_factory=dict)


class _Reader:
    def __init__(self, data: bytes | memoryview):
        self.data = memoryview(data)
        self.pos = 0

    def take(self, count: int) -> memoryview:
        end = self.pos + count
        if end > len(self.data):
            raise InputError("truncated class file")
        start = self.pos
        self.pos = end
        return self.data[start:end]

    def u1(self) -> int:
        return self.take(1)[0]

    def u2(self) -> int:
        return struct.unpack(">H", self.take(2))[0]

    def u4(self) -> int:
        return struct.unpack(">I", self.take(4))[0]

    def end(self) -> bool:
        return self.pos == len(self.data)


class _Pool:
    def __init__(self, reader: _Reader):
        count = reader.u2()
        self.entries = [None] * count
        index = 1
        while index < count:
            tag = reader.u1()
            value = None
            if tag == 1:
                # JVM modified UTF-8 uses a special NUL and encodes supplementary characters as pairs.
                data = bytes(reader.take(reader.u2())).replace(b"\xc0\x80", b"\0")
                try:
                    value = data.decode("utf-8")
                except UnicodeDecodeError:
                    value = data.decode("utf-8", "surrogatepass").encode("utf-16-le", "surrogatepass").decode("utf-16-le", "replace")
            elif tag in {3, 4}:
                value = struct.unpack(">i" if tag == 3 else ">f", reader.take(4))[0]
            elif tag in {5, 6}:
                reader.take(8)
                if index + 1 >= count:
                    raise InputError("invalid two-slot constant")
            elif tag in {7, 8, 16, 19, 20}:
                value = reader.u2()
            elif tag in {9, 10, 11, 12, 17, 18}:
                value = (reader.u2(), reader.u2())
            elif tag == 15:
                reader.take(3)
            else:
                raise InputError(f"unsupported class constant pool tag {tag}")
            self.entries[index] = (tag, value)
            index += 2 if tag in {5, 6} else 1

    def get(self, index: int, tag: int):
        if not 0 < index < len(self.entries) or not self.entries[index] or self.entries[index][0] != tag:
            raise InputError("invalid class constant pool reference")
        return self.entries[index][1]

    def utf(self, index: int) -> str:
        return self.get(index, 1)

    def owner(self, index: int) -> str:
        return self.utf(self.get(index, 7))

    def member(self, index: int, tag: int) -> Symbol:
        owner, pair = self.get(index, tag)
        name, descriptor = self.get(pair, 12)
        return Symbol(self.owner(owner), self.utf(name), self.utf(descriptor))

    def literal(self, index: int) -> Instruction:
        if not 0 < index < len(self.entries) or self.entries[index] is None:
            raise InputError("invalid ldc constant pool reference")
        tag, value = self.entries[index]
        if tag == 3:
            return Instruction("const", value)
        if tag == 4:
            return Instruction("float", value)
        if tag == 8:
            return Instruction("const", self.utf(value))
        return Instruction("other")

    def refs(self) -> set[Symbol]:
        refs = set()
        for entry in self.entries:
            if entry is None:
                continue
            tag, value = entry
            if tag == 7:
                name = self.utf(value)
                if CLASS_NAME.fullmatch(name):
                    refs.add(Symbol(name))
            elif tag in {9, 10, 11}:
                owner = self.owner(value[0])
                name, descriptor = self.get(value[1], 12)
                if CLASS_NAME.fullmatch(owner):
                    refs.add(Symbol(owner, self.utf(name), self.utf(descriptor)))
            elif tag == 1 and value.startswith(("L", "[", "(", "<")) and ";" in value:
                # Class, member, generic and annotation descriptors are stored in UTF8 constants.
                # Descriptor-shaped strings can be unused, so these are static hints, not a runtime trace.
                refs.update(Symbol(owner) for owner in DESCRIPTOR_CLASSES.findall(value))
        return refs


def _annotations(payload: memoryview, pool: _Pool) -> tuple[Annotation, ...]:
    reader = _Reader(payload)

    def element(depth: int) -> object:
        if depth > 64:
            raise InputError("class annotation is nested too deeply")
        tag = chr(reader.u1())
        if tag == "s":
            return pool.utf(reader.u2())
        if tag == "Z":
            value = pool.get(reader.u2(), 3)
            if value not in (0, 1):
                raise InputError("invalid class annotation boolean")
            return bool(value)
        if tag in "BCDFIJSc":
            reader.u2()
        elif tag == "e":
            reader.take(4)
        elif tag == "@":
            annotation(depth + 1)
        elif tag == "[":
            for _ in range(reader.u2()):
                element(depth + 1)
        else:
            raise InputError(f"invalid class annotation element tag {tag!r}")
        # Most annotation values are not used by the audit. Keep the element name so
        # narrow converters can reject options they don't understand.
        return None

    def annotation(depth: int = 0) -> Annotation:
        kind = pool.utf(reader.u2())
        values = {}
        for _ in range(reader.u2()):
            name = pool.utf(reader.u2())
            if name in values:
                raise InputError("duplicate class annotation element")
            values[name] = element(depth)
        return Annotation(kind, values)

    result = tuple(annotation() for _ in range(reader.u2()))
    if not reader.end():
        raise InputError("trailing data in class annotations")
    return result


def _straight_line(code_attr: memoryview, pool: _Pool) -> tuple[Instruction, ...]:
    """Decode only the JVM instructions supported by the recipe data evaluator."""
    attr = _Reader(code_attr)
    max_stack, max_locals, count = attr.u2(), attr.u2(), attr.u4()
    if not 0 < count <= 32_768 or max_stack > 256 or max_locals > 16:
        raise InputError("recipe method exceeds straight-line bytecode limits")
    reader = _Reader(attr.take(count))
    if attr.u2():
        raise InputError("recipe methods cannot have exception handlers")
    for _ in range(attr.u2()):
        name = pool.utf(attr.u2())
        if name not in {"LineNumberTable", "LocalVariableTable", "LocalVariableTypeTable"}:
            raise InputError(f"unsupported recipe method code attribute {name!r}")
        attr.take(attr.u4())
    if not attr.end():
        raise InputError("trailing data in recipe Code attribute")
    ops = []
    members = {0xB2: ("getstatic", 9), 0xB3: ("putstatic", 9), 0xB4: ("getfield", 9),
               0xB6: ("invoke", 10), 0xB7: ("constructor", 10), 0xB8: ("static", 10)}
    names = {0x2A: "this", 0x2B: "argument", 0x53: "aastore", 0x57: "pop", 0x59: "dup", 0xB1: "return"}
    while not reader.end():
        offset = reader.pos
        op = reader.u1()
        if op in names:
            value = Instruction(names[op])
        elif 0x02 <= op <= 0x08:
            value = Instruction("const", op - 3)
        elif 0x0B <= op <= 0x0D:
            value = Instruction("float", float(op - 0x0B))
        elif op in {0x10, 0x11}:
            value = Instruction("const", int.from_bytes(reader.take(1 if op == 0x10 else 2), "big", signed=True))
        elif op in {0x12, 0x13}:
            value = pool.literal(reader.u1() if op == 0x12 else reader.u2())
        elif op in members:
            name, tag = members[op]
            value = Instruction(name, pool.member(reader.u2(), tag))
        elif op in {0xBB, 0xBD}:
            value = Instruction("new" if op == 0xBB else "array", pool.owner(reader.u2()))
        else:
            raise InputError(f"unsupported recipe opcode 0x{op:02x} at byte {offset}")
        if value.op == "other" or (value.op == "return" and not reader.end()):
            raise InputError(f"unsupported constant or early return at byte {offset}")
        ops.append(value)
    if ops[-1].op != "return":
        raise InputError("recipe method must end in a void return")
    return tuple(ops)


def _initializer(code_attr: memoryview, pool: _Pool) -> tuple[Instruction, ...]:
    """Decode a straight-line static initializer as data. This never evaluates JVM calls."""
    attr = _Reader(code_attr)
    attr.take(4)  # max_stack and max_locals
    count = attr.u4()
    if not 0 < count <= 65535:
        raise InputError("invalid initializer code size")
    reader = _Reader(attr.take(count))
    if attr.u2():
        raise InputError("exception handlers are not supported in catalog initializers")
    for _ in range(attr.u2()):
        pool.utf(attr.u2())
        attr.take(attr.u4())
    if not attr.end():
        raise InputError("trailing data in catalog Code attribute")
    ops = []
    one = {0x10, 0x12, *range(0x15, 0x1A), *range(0x36, 0x3B), 0xBC}
    two = {0x11, 0x13, 0x14, 0x84, *range(0xB2, 0xB9), 0xBB, 0xBD, 0xC0, 0xC1}
    while not reader.end():
        op = reader.u1()
        if 0x99 <= op <= 0xAB or op == 0xC4 or op >= 0xC6:
            raise InputError(f"unsupported control flow or opcode 0x{op:02x} in catalog initializer")
        if op in {0x10, 0x11}:
            ops.append(Instruction("const", int.from_bytes(reader.take(1 if op == 0x10 else 2), "big", signed=True)))
        elif 0x02 <= op <= 0x08:
            ops.append(Instruction("const", op - 3))
        elif 0x0B <= op <= 0x0D:
            ops.append(Instruction("float", float(op - 0x0B)))
        elif op == 0x01:
            ops.append(Instruction("const", None))
        elif op in {0x12, 0x13}:
            ops.append(pool.literal(reader.u1() if op == 0x12 else reader.u2()))
        elif op in {0xBB, 0xBD}:
            ops.append(Instruction("new" if op == 0xBB else "array", pool.owner(reader.u2())))
        elif op in {0xB2, 0xB3, 0xB7, 0xB8}:
            index = reader.u2()
            # invokestatic can also target an interface. Neither catalog format
            # treats interface calls as data-producing declarations.
            if op == 0xB8 and 0 < index < len(pool.entries) and (pool.entries[index] or (None,))[0] == 11:
                pool.member(index, 11)
                ops.append(Instruction("other"))
            else:
                ops.append(Instruction({0xB2: "getstatic", 0xB3: "putstatic", 0xB7: "constructor", 0xB8: "static"}[op],
                                       pool.member(index, 10 if op >= 0xB7 else 9)))
        elif op in {0x59, 0x53}:
            ops.append(Instruction("dup" if op == 0x59 else "aastore"))
        else:
            width = 1 if op in one else 2 if op in two else 4 if op in {0xB9, 0xBA} else 3 if op == 0xC5 else 0
            reader.take(width)
            ops.append(Instruction("return" if op == 0xB1 else "other"))
    if not ops or ops[-1].op != "return" or any(op.op == "return" for op in ops[:-1]):
        raise InputError("catalog initializer must have one final return")
    return tuple(ops)


def read_class(data: bytes, *, collect_references: bool = True, capture_initializer: bool = False,
               capture_program: bool = False) -> ClassFile:
    reader = _Reader(data)
    if reader.u4() != 0xCAFEBABE:
        raise InputError("not a JVM class file")
    minor, major = reader.u2(), reader.u2()
    if capture_program and (minor != 0 or not 49 <= major <= 52):
        raise InputError("recipe conversion requires ordinary Java 5 through 8 class files")
    try:
        pool = _Pool(reader)
    except UnicodeDecodeError as exc:
        raise InputError("invalid modified UTF-8 in class constants") from exc
    access = reader.u2()
    owner = pool.owner(reader.u2())
    if not CLASS_NAME.fullmatch(owner):
        raise InputError(f"unsupported JVM class name {owner!r}")
    superclass = reader.u2()
    parents = (pool.owner(superclass),) if superclass else ()
    parents += tuple(pool.owner(reader.u2()) for _ in range(reader.u2()))

    members, code, member_annotations = {}, {}, {}
    initializer = None
    for table in ("fields", "methods"):
        for _ in range(reader.u2()):
            flags, name, descriptor = reader.u2(), pool.utf(reader.u2()), pool.utf(reader.u2())
            key = name, descriptor
            if capture_program and (key in members or not (METHOD_DESCRIPTOR if table == "methods" else FIELD_DESCRIPTOR).fullmatch(descriptor)):
                raise InputError(f"invalid or duplicate recipe class member {name}{descriptor}")
            members[key] = flags
            for _ in range(reader.u2()):
                attribute = pool.utf(reader.u2())
                payload = reader.take(reader.u4())
                if capture_initializer and table == "methods" and key == ("<clinit>", "()V") and attribute == "Code":
                    if initializer is not None:
                        raise InputError("multiple class initializers")
                    initializer = _initializer(payload, pool)
                if not capture_program:
                    continue
                if table == "methods" and attribute == "Code" and key not in code:
                    try:
                        code[key] = _straight_line(payload, pool)
                    except InputError as exc:
                        raise InputError(f"{owner}.{name}{descriptor}: {exc}") from exc
                elif attribute == "RuntimeVisibleAnnotations" and key not in member_annotations:
                    member_annotations[key] = _annotations(payload, pool)
                else:
                    raise InputError(f"unsupported or duplicate recipe class member attribute {attribute!r}")

    mod, annotations = None, None
    for _ in range(reader.u2()):
        name = pool.utf(reader.u2())
        payload = reader.take(reader.u4())
        if capture_program:
            if name == "RuntimeVisibleAnnotations" and annotations is None:
                annotations = _annotations(payload, pool)
            elif name not in {"SourceFile", "InnerClasses"}:
                raise InputError(f"unsupported or duplicate recipe class attribute {name!r}")
        if collect_references and name in {"RuntimeVisibleAnnotations", "RuntimeInvisibleAnnotations"}:
            for annotation in _annotations(payload, pool):
                if annotation.descriptor == LEGACY_MOD_ANNOTATION:
                    mod = {key: value for key, value in annotation.values.items() if isinstance(value, str)}
    if not reader.end():
        raise InputError("trailing data in class file")
    return ClassFile(owner, access, parents, members, pool.refs() if collect_references else set(), mod, initializer,
                     annotations or (), member_annotations, code)


def class_entries(paths) -> dict[str, str]:
    # Ignore multi-release overlays, module declarations, and entries that are not at binary-name paths.
    return {path[:-6]: path for path in paths if path.endswith(".class") and not path.startswith("META-INF/")
            and path != "module-info.class" and CLASS_NAME.fullmatch(path[:-6])}
