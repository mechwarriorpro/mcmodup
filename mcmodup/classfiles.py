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


@dataclass
class ClassFile:
    owner: str
    access: int
    parents: tuple[str, ...]
    members: dict[tuple[str, str], int]
    references: set[Symbol] = field(default_factory=set)
    legacy_mod: dict[str, str] | None = None


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
            elif tag in {3, 4, 5, 6}:
                reader.take(8 if tag in {5, 6} else 4)
                if tag in {5, 6} and index + 1 >= count:
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


def _annotations(payload: memoryview, pool: _Pool) -> dict[str, str] | None:
    reader = _Reader(payload)

    def element(depth: int) -> str | None:
        if depth > 64:
            raise InputError("class annotation is nested too deeply")
        tag = chr(reader.u1())
        if tag == "s":
            return pool.utf(reader.u2())
        if tag in "BCDFIJSZc":
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
        return None

    def annotation(depth: int = 0) -> tuple[str, dict[str, str]]:
        kind = pool.utf(reader.u2())
        values = {}
        for _ in range(reader.u2()):
            name = pool.utf(reader.u2())
            value = element(depth)
            if value is not None and kind == LEGACY_MOD_ANNOTATION:
                values[name] = value
        return kind, values

    mod = None
    for _ in range(reader.u2()):
        kind, values = annotation()
        if kind == LEGACY_MOD_ANNOTATION:
            mod = values
    if not reader.end():
        raise InputError("trailing data in class annotations")
    return mod


def read_class(data: bytes, *, collect_references: bool = True) -> ClassFile:
    reader = _Reader(data)
    if reader.u4() != 0xCAFEBABE:
        raise InputError("not a JVM class file")
    reader.take(4)  # Minor and major version. Unknown constant pool tags still fail explicitly.
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

    members = {}
    for _ in ("fields", "methods"):
        for _ in range(reader.u2()):
            flags, name, descriptor = reader.u2(), pool.utf(reader.u2()), pool.utf(reader.u2())
            members[name, descriptor] = flags
            for _ in range(reader.u2()):
                reader.u2()  # Attribute name
                reader.take(reader.u4())

    mod = None
    for _ in range(reader.u2()):
        name = pool.utf(reader.u2())
        payload = reader.take(reader.u4())
        if collect_references and name in {"RuntimeVisibleAnnotations", "RuntimeInvisibleAnnotations"}:
            mod = _annotations(payload, pool) or mod
    if not reader.end():
        raise InputError("trailing data in class file")
    return ClassFile(owner, access, parents, members, pool.refs() if collect_references else set(), mod)


def class_entries(paths) -> dict[str, str]:
    # Ignore multi-release overlays, module declarations, and entries that are not at binary-name paths.
    return {path[:-6]: path for path in paths if path.endswith(".class") and not path.startswith("META-INF/")
            and path != "module-info.class" and CLASS_NAME.fullmatch(path[:-6])}
