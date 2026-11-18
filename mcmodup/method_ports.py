"""Transplant reviewed legacy method bodies behind an explicit, native adapter ABI.

This is not a verifier or general JVM remapper. Only a bounded instruction set is
accepted, no original class is loaded, and nothing outside the named bodies is
copied. Branch offsets, local slots and stack maps are retained. The game tests
still have to exercise the adapted behavior.
"""

from dataclasses import dataclass, field
import hashlib
from pathlib import Path
import re
import struct

from .classfiles import CLASS_LIMIT, CLASS_NAME, DESCRIPTOR_CLASSES, FIELD_DESCRIPTOR, METHOD_DESCRIPTOR, Symbol, _Pool, _Reader, read_class
from .inputs import InputError, ModInput, open_input


PROVENANCE = "META-INF/mcmodup/linked-methods.json"
CLASS_ROOT = "mcmodup_kernel/"
_JDK_TYPES = frozenset({"java/lang/Object", "java/lang/String", "java/util/Random"})
_NAME = re.compile(r"[A-Za-z_$][A-Za-z0-9_$]{0,127}")
_OWNER = re.compile(r"[A-Za-z_$][A-Za-z0-9_$]*(?:/[A-Za-z_$][A-Za-z0-9_$]*)+")
_SHORT_BRANCHES = frozenset(range(0x99, 0xa8)) | {0xc6, 0xc7}
_ONE_BYTE_OPERAND = frozenset(range(0x15, 0x1a)) | frozenset(range(0x36, 0x3b)) | {0x10}
# No allocations, monitor operations, jsr/ret, switches, wide, or dynamic linking.
_PLAIN = (frozenset(range(0, 0x10)) | frozenset(range(0x1a, 0x36)) |
          frozenset(range(0x3b, 0x84)) | frozenset(range(0x85, 0x99)) |
          frozenset(range(0xac, 0xb2)) | {0xbe})
_MEMBERS = {0xb2: 9, 0xb3: 9, 0xb4: 9, 0xb5: 9, 0xb6: 10, 0xb8: 10}
_REDIRECT_OPS = {"getstatic": 0xb2, "putstatic": 0xb3, "getfield": 0xb4, "putfield": 0xb5,
                 "invokevirtual": 0xb6, "invokestatic": 0xb8}
_OP_NAMES = {op: name for name, op in _REDIRECT_OPS.items()}
_DEBUG = frozenset({"LineNumberTable", "LocalVariableTable", "LocalVariableTypeTable"})


def _u2(value: int) -> bytes:
    return struct.pack(">H", value)


def _attr(index: int, data: bytes) -> bytes:
    return struct.pack(">HI", index, len(data)) + data


@dataclass(frozen=True)
class MethodPort:
    owner: str
    output: str
    types: dict[str, str]
    methods: dict[Symbol, str]
    members: dict[Symbol, Symbol]
    redirects: dict[tuple[int, Symbol], Symbol] = field(default_factory=dict)

    def type(self, name: str) -> str:
        if name.startswith("[") and FIELD_DESCRIPTOR.fullmatch(name):
            return self.descriptor(name)
        if name in _JDK_TYPES:
            return name
        if name in self.types:
            return self.types[name]
        raise InputError(f"No native adapter type for {name!r}")

    def descriptor(self, text: str) -> str:
        return DESCRIPTOR_CLASSES.sub(lambda m: "L" + self.type(m[1]), text)


def parse_method_ports(value: object, ids: dict[str, str]) -> tuple[MethodPort, ...]:
    if not isinstance(value, list) or not 0 <= len(value) <= 32:
        raise InputError("method_imports must be a list of at most 32 method groups")
    result, outputs = [], set()
    required = {"owner", "output", "types", "methods", "members"}
    for row in value:
        if not isinstance(row, dict) or not required <= row.keys() <= required | {"redirects"}:
            raise InputError("A method import needs owner, output, types, methods and members (optionally redirects)")
        owner, output, types, methods, members = (row[key] for key in ("owner", "output", "types", "methods", "members"))

        def internal(name: object) -> bool:
            return isinstance(name, str) and len(name) <= 240 and bool(CLASS_NAME.fullmatch(name))

        def local(name: object) -> bool:
            return (isinstance(name, str) and len(name) <= 240 and bool(_OWNER.fullmatch(name)) and
                    any(name.startswith(f"{CLASS_ROOT}{key}/") for key in ids))

        if not internal(owner) or not local(output) or output.casefold() in outputs:
            raise InputError("Expected a legacy class and a distinct mcmodup_kernel/<native-id>/ output class")
        if (not isinstance(types, dict) or len(types) > 64 or any(not internal(k) or k in _JDK_TYPES or not local(v)
                                                                 for k, v in types.items()) or
                len(set(types.values())) != len(types) or output in types.values()):
            raise InputError("Map legacy types one-to-one to local native adapter classes, not game or JDK classes")
        if not isinstance(methods, dict) or not 1 <= len(methods) <= 64 or not isinstance(members, dict) or len(members) > 128:
            raise InputError("Supply 1–64 chosen methods and at most 128 explicit adapter member bindings")
        selected, bindings, redirects = {}, {}, {}
        for old, new in methods.items():
            symbol = Symbol.parse(old)
            if (symbol.owner != owner or symbol.kind != "method" or not _NAME.fullmatch(symbol.name) or
                    not isinstance(new, str) or not _NAME.fullmatch(new)):
                raise InputError(f"Expected an ordinary method from {owner!r} and a generated method name, not {old!r}")
            selected[symbol] = new
        port = MethodPort(owner, output, types, selected, bindings, redirects)
        for old, new in members.items():
            if not isinstance(new, str):
                raise InputError(f"Expected an explicit target member for {old!r}")
            src, dst = Symbol.parse(old), Symbol.parse(new)
            if (src.kind not in {"field", "method"} or src.kind != dst.kind or not _NAME.fullmatch(dst.name) or
                    dst.owner not in types.values() or dst.owner != port.type(src.owner) or
                    dst.descriptor != port.descriptor(src.descriptor)):
                raise InputError(f"Adapter binding must keep the member kind, receiver and mapped descriptor: {old!r} → {new!r}")
            bindings[src] = dst
        rules = row.get("redirects", {})
        if not isinstance(rules, dict) or rules.keys() - _REDIRECT_OPS.keys():
            raise InputError("Use supported bytecode opcode names in redirects")
        for opcode, edges in rules.items():
            if not isinstance(edges, dict) or not edges or len(edges) + len(redirects) + len(bindings) > 128:
                raise InputError("Supply at most 128 adapter member bindings and call redirects combined")
            op = _REDIRECT_OPS[opcode]
            for old, new in edges.items():
                if not isinstance(new, str):
                    raise InputError(f"Expected a static helper target for {old!r}")
                src, dst = Symbol.parse(old), Symbol.parse(new)
                if (src.kind != ("field" if _MEMBERS[op] == 9 else "method") or not _NAME.fullmatch(src.name) or
                        dst.kind != "method" or not _NAME.fullmatch(dst.name) or not local(dst.owner) or dst.owner == output):
                    raise InputError(f"Invalid {opcode} redirect {old!r} → {new!r}; target must be a local static helper")
                # Like Connector's field/call redirects: consume exactly what the
                # original opcode would have consumed, receiver first, leaving
                # the same value (if any). This preserves frames and branches.
                receiver = f"L{port.type(src.owner)};" if op in {0xb4, 0xb5, 0xb6} else ""
                signature = port.descriptor(src.descriptor)
                if op in {0xb2, 0xb4}:
                    signature = f"({receiver}){signature}"
                elif op in {0xb3, 0xb5}:
                    signature = f"({receiver}{signature})V"
                else:
                    signature = "(" + receiver + signature[1:]
                if dst.descriptor != signature:
                    raise InputError(f"{opcode} redirect must keep the mapped stack shape {signature}: {old!r} → {new!r}")
                redirects[op, src] = dst
        outputs.add(output.casefold())
        result.append(port)
    return tuple(result)


class _OutputPool:
    def __init__(self):
        self.entries = []
        self.index = {}

    def add(self, tag: int, data: bytes) -> int:
        entry = bytes([tag]) + data
        if entry not in self.index:
            if len(self.entries) + (2 if tag in {5, 6} else 1) > 65534:
                raise InputError("Generated method constant pool is too large; split the group")
            self.index[entry] = len(self.entries) + 1
            self.entries.append(entry)
            if tag in {5, 6}:
                self.entries.append(b"")
        return self.index[entry]

    def utf(self, name: str) -> int:
        return self.raw_utf(name.encode("ascii"))  # generated JVM names/descriptors only

    def raw_utf(self, data: bytes) -> int:
        return self.add(1, _u2(len(data)) + data)

    def cls(self, owner: str) -> int:
        return self.add(7, _u2(self.utf(owner)))

    def member(self, tag: int, symbol: Symbol) -> int:
        owner = self.cls(symbol.owner)
        pair = self.add(12, _u2(self.utf(symbol.name)) + _u2(self.utf(symbol.descriptor)))
        return self.add(tag, _u2(owner) + _u2(pair))

    def bytes(self) -> bytes:
        return _u2(len(self.entries) + 1) + b"".join(self.entries)


def _raw_constants(data: bytes) -> dict[int, bytes]:
    # Keep exact modified UTF-8 and numeric bits. Decoding then re-encoding a
    # literal can change Java strings (including unpaired surrogates) or NaNs.
    reader = _Reader(data)
    reader.take(8)
    end = reader.u2()
    entries, i = {}, 1
    while i < end:
        tag = reader.u1()
        length = {3: 4, 4: 4, 5: 8, 6: 8, 7: 2, 8: 2, 9: 4, 10: 4, 11: 4, 12: 4,
                  15: 3, 16: 2, 17: 4, 18: 4, 19: 2, 20: 2}
        entries[i] = bytes(reader.take(reader.u2() if tag == 1 else length[tag]))
        i += 2 if tag in {5, 6} else 1
    return entries


def _stack_map(data: memoryview, pool: _Pool, output: _OutputPool, port: MethodPort, starts: set[int]) -> bytes:
    reader, result = _Reader(data), bytearray(data)

    def typ() -> None:
        tag = reader.u1()
        if tag == 7:
            at = reader.pos
            result[at:at + 2] = _u2(output.cls(port.type(pool.owner(reader.u2()))))
        elif tag > 6 or tag == 6:  # initialized receivers only; no new/constructor copying
            raise InputError("Unsupported verification type in method stack map")

    offset = -1
    for _ in range(reader.u2()):
        frame = reader.u1()
        if frame <= 127:
            delta = frame % 64
            if frame >= 64:
                typ()
        elif frame >= 247:
            delta = reader.u2()
            if frame == 247:
                typ()
            elif 252 <= frame <= 254:
                for _ in range(frame - 251):
                    typ()
            elif frame == 255:
                for _ in range(reader.u2()):
                    typ()
                for _ in range(reader.u2()):
                    typ()
        else:
            raise InputError("Unknown method stack-map frame")
        offset += delta + 1
        if offset not in starts:
            raise InputError("Invalid method stack-map offset")
    if not reader.end():
        raise InputError("Trailing method stack-map data")
    return bytes(result)


def _code(data: memoryview, pool: _Pool, raw: dict[int, bytes], output: _OutputPool, port: MethodPort,
          uses: dict[Symbol, set[int]], bound: set[Symbol], rewritten: dict[tuple[int, Symbol], int]) -> bytes:
    attr = _Reader(data)
    limits = bytes(attr.take(4))
    length = attr.u4()
    if not 0 < length <= 8192:
        raise InputError("Method body exceeds the transplant instruction limit")
    code = _Reader(attr.take(length))
    result = bytearray(code.data)
    starts, jumps = set(), set()
    while not code.end():
        start, op = code.pos, code.u1()
        starts.add(start)
        if op in _PLAIN:
            continue
        if op in _ONE_BYTE_OPERAND:
            code.take(1)
        elif op in {0x11, 0x84}:
            code.take(2)
        elif op in _SHORT_BRANCHES:
            jumps.add(start + struct.unpack(">h", code.take(2))[0])
        elif op in _MEMBERS:
            source = pool.member(code.u2(), _MEMBERS[op])
            edge = (op, source)
            if edge in port.redirects:
                target = port.redirects[edge]
                rewritten[edge] = rewritten.get(edge, 0) + 1
                result[start] = op = 0xb8  # same instruction width; validated stack shape
            else:
                target = port.members.get(source)
                if target is None:
                    raise InputError(f"Unmapped bytecode member {source.key} ({_OP_NAMES[op]})")
                bound.add(source)
            uses.setdefault(target, set()).add(op)
            result[start + 1:code.pos] = _u2(output.member(_MEMBERS[op], target))
        elif op in {0x12, 0x13, 0x14}:
            index = code.u1() if op == 0x12 else code.u2()
            if not 0 < index < len(pool.entries) or not pool.entries[index]:
                raise InputError("Invalid method literal index")
            tag, value = pool.entries[index]
            if tag not in ({5, 6} if op == 0x14 else {3, 4, 8}):
                raise InputError("Only numeric and string literals can be transplanted")
            if tag == 8:
                pool.utf(value)  # reject invalid or wrong-tag string targets
                replacement = output.add(tag, _u2(output.raw_utf(raw[value])))
            else:
                replacement = output.add(tag, raw[index])
            if op == 0x12 and replacement > 255:
                raise InputError("Method import would widen an ldc and move branch offsets; split the group")
            result[start + 1:code.pos] = bytes([replacement]) if op == 0x12 else _u2(replacement)
        else:
            raise InputError(f"Unsupported opcode 0x{op:02x} at bytecode offset {start}")
    if not jumps <= starts or attr.u2():
        raise InputError("Invalid branch target or unsupported exception table")
    frames = []
    for _ in range(attr.u2()):
        name, payload = pool.utf(attr.u2()), attr.take(attr.u4())
        if name == "StackMapTable" and not frames:
            frames.append(_attr(output.utf(name), _stack_map(payload, pool, output, port, starts)))
        elif name not in _DEBUG:
            raise InputError(f"Unsupported or duplicate method code attribute {name!r}")
    if not attr.end():
        raise InputError("Trailing method Code data")
    return limits + struct.pack(">I", length) + result + b"\0\0" + _u2(len(frames)) + b"".join(frames)


@dataclass(frozen=True)
class ImportedMethods:
    path: str
    bytecode: bytes
    uses: dict[Symbol, set[int]]
    types: frozenset[str]
    originals: tuple[str, ...]
    call_redirects: tuple[tuple[str, str, str, int], ...]

    def report(self) -> dict:
        report = {"class": self.path.removesuffix(".class"), "sha256": hashlib.sha256(self.bytecode).hexdigest(),
                  "methods": list(self.originals), "adapter_members": sorted(s.key for s in self.uses)}
        if self.call_redirects:
            report["call_redirects"] = [{"opcode": op, "source": src, "target": dst, "sites": count}
                                        for op, src, dst, count in self.call_redirects]
        return report


def transplant(data: bytes, port: MethodPort) -> ImportedMethods:
    reader = _Reader(data)
    if reader.u4() != 0xcafebabe or reader.u2() != 0 or not 49 <= (major := reader.u2()) <= 52:
        raise InputError("Method imports require ordinary Java 5–8 bytecode")
    try:
        pool = _Pool(reader)
    except UnicodeError as exc:
        raise InputError("Invalid legacy class modified UTF-8") from exc
    raw = _raw_constants(data)
    flags, owner = reader.u2(), pool.owner(reader.u2())
    if owner != port.owner or flags & 0x2200:
        raise InputError("Method source is not the expected ordinary class")
    reader.u2()  # superclass is deliberately not pulled into the generated utility class
    reader.take(reader.u2() * 2)
    compiled, found, emitted, uses, bound, rewritten = [], set(), set(), {}, set(), {}
    output = _OutputPool()
    for table in ("field", "method"):
        for _ in range(reader.u2()):
            access, name, descriptor = reader.u2(), pool.utf(reader.u2()), pool.utf(reader.u2())
            attrs = [(pool.utf(reader.u2()), reader.take(reader.u4())) for _ in range(reader.u2())]
            key = Symbol(owner, name, descriptor)
            if table != "method" or key not in port.methods:
                continue
            # No synchronized, bridge, native, abstract, or loader entry
            # methods. Even a private body is OK if all of its edges bind.
            if key in found or access & ~0x1f or (access & 7) not in {0, 1, 2, 4}:
                raise InputError(f"Duplicate or unsupported declaration for {key.key}")
            if len(attrs) != 1 or attrs[0][0] != "Code":
                raise InputError(f"Method import requires a single Code attribute without loader metadata: {key.key}")
            try:
                signature = port.descriptor(descriptor)
                if not access & 8:
                    signature = "(L" + port.type(owner) + ";" + signature[1:]
                target = (port.methods[key], signature)
                if not METHOD_DESCRIPTOR.fullmatch(signature) or target in emitted:
                    raise InputError("Conflicting or invalid generated method signature")
                payload = _code(attrs[0][1], pool, raw, output, port, uses, bound, rewritten)
            except InputError as exc:
                raise InputError(f"{key.key}: {exc}") from exc
            found.add(key)
            emitted.add(target)
            compiled.append(struct.pack(">HHHH", 9, output.utf(target[0]), output.utf(target[1]), 1) +
                            _attr(output.utf("Code"), payload))
    for _ in range(reader.u2()):
        reader.take(2)
        reader.take(reader.u4())
    if not reader.end() or found != port.methods.keys() or bound != port.members.keys():
        raise InputError("Missing/extra method import bindings or malformed source class")
    if unused := port.redirects.keys() - rewritten.keys():
        detail = ", ".join(f"{_OP_NAMES[op]} {symbol.key}" for op, symbol in sorted(unused))
        raise InputError(f"Unused bytecode redirects: {detail}")
    header = struct.pack(">IHH", 0xcafebabe, 0, major)
    declaration = struct.pack(">HHHHHH", 0x31, output.cls(port.output), output.cls("java/lang/Object"), 0, 0, len(compiled))
    redirects = tuple(sorted((_OP_NAMES[op], symbol.key, port.redirects[op, symbol].key, count)
                             for (op, symbol), count in rewritten.items()))
    types = frozenset(port.types.values()) | {target.owner for target in uses}
    return ImportedMethods(port.output + ".class", header + output.bytes() + declaration + b"".join(compiled) + b"\0\0",
                           uses, types, tuple(sorted(key.key for key in found)), redirects)


def prepare(source: ModInput, workspace: Path, ports: tuple[MethodPort, ...]) -> tuple[ImportedMethods, ...]:
    result = []
    for port in ports:
        member = port.owner + ".class"
        if member not in source.files:
            raise InputError(f"Missing or ambiguous legacy method owner {member!r}")
        imported = transplant(source.read(member, CLASS_LIMIT), port)
        path = workspace / "src/main/resources" / imported.path
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("xb") as stream:
            stream.write(imported.bytecode)
        result.append(imported)
    return tuple(result)


def verify(output: Path, imports: tuple[ImportedMethods, ...]) -> None:
    with open_input(output) as archive:
        checked = {}
        for group in imports:
            if group.path not in archive.files or archive.read(group.path, CLASS_LIMIT) != group.bytecode:
                raise InputError(f"Built JAR did not retain generated method class {group.path!r}")
            for owner in sorted(group.types - checked.keys()):
                path = owner + ".class"
                if path not in archive.files:
                    raise InputError(f"Built JAR is missing native adapter class {owner!r}")
                info = read_class(archive.read(path, CLASS_LIMIT))
                if info.owner != owner or not info.access & 1 or info.access & 0x0200:
                    raise InputError(f"Native adapter must be a public class at {path!r}")
                checked[owner] = info
            for target, opcodes in group.uses.items():
                flags = checked[target.owner].members.get((target.name, target.descriptor), 0)
                if (not flags & 1 or flags & 0x0500 or any(bool(flags & 8) != (op in {0xb2, 0xb3, 0xb8}) for op in opcodes) or
                        (flags & 0x10 and opcodes & {0xb3, 0xb5})):
                    raise InputError(f"Built JAR is missing a callable native adapter member {target.key}")
