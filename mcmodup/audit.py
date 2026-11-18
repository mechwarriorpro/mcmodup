"""Identify legacy metadata, version evidence, and API areas that need human review."""

from collections.abc import Iterable
from dataclasses import dataclass, field
import json
from pathlib import PurePosixPath
import re

from . import MINECRAFT_VERSION, NEOFORGE_VERSION, SOURCE_VERSIONS
from .api_rules import rules_for, source_symbols
from .classfiles import Symbol
from .inputs import InputError, ModInput


RULES = [
    ("lifecycle", r"\b(?:FML(?:Pre|Post)?InitializationEvent|BaseMod|ModLoader)\b|@Mod\.(?:EventHandler|PreInit|Init|PostInit)\b",
     "Move initialization into a NeoForge @Mod entry point; wire lifecycle listeners to the mod event bus."),
    ("forge_api", r"\b(?:cpw\.mods\.fml|net\.minecraftforge)\.",
     "Check Forge/FML imports against 1.21.1 NeoForge APIs and Mojang mappings. Don't just rename packages."),
    ("registries", r"\bGameRegistry\s*\.\s*register(?:Block|Item|TileEntity)\b|\.(?:blockID|itemID)\b|\bnew\s+(?:Item|Block)\s*\(\s*\d",
     "Register named objects through DeferredRegister. Preserve valid registry IDs where possible; plan and test any remaps."),
    ("recipes", r"\b(?:GameRegistry\s*\.\s*add(?:Recipe|ShapelessRecipe|Smelting)|FurnaceRecipes)\b",
     "Recreate recipes as 1.21.1 data files under data/<namespace>/recipe, or use data generation."),
    ("localization", r"\bLanguageRegistry\b",
     "Move names into assets/<namespace>/lang JSON and check modern translation keys for each registered object."),
    ("networking", r"\b(?:NetworkMod|IPacketHandler|Packet250CustomPayload|SimpleNetworkWrapper|IMessageHandler|PacketDispatcher)\b",
     "Rebuild packets with CustomPacketPayload, StreamCodec and RegisterPayloadHandlersEvent; recheck sides and validation."),
    ("client", r"\b(?:SidedProxy|RenderingRegistry|ISimpleBlockRenderingHandler|IIcon(?:Register)?|set(?:Block|Item)TextureName)\b|net\.minecraft\.client\.",
     "Separate client-only code, then remake models, renderers, and screens. Check dedicated-server startup."),
    ("world", r"\b(?:WorldGenMinable|IWorldGenerator|registerWorldGenerator|EntityRegistry|TileEntity)\b",
     "Rework worldgen as features/placement and rebuild entity and block entity registrations for 1.21.1."),
    ("saved_data", r"\b(?:NBTTagCompound|IExtendedEntityProperties|getItemDamage|setItemDamage)\b",
     "Review saved-data formats and ItemStack data components. Test on copies; do not open original worlds with a scaffold."),
    ("configuration", r"\b(?:net\.minecraftforge\.common\.config|MinecraftForge\.EVENT_BUS|ForgeSubscribe)\b",
     "Rebuild legacy configs with ModConfigSpec and verify modern event types and event-bus placement."),
    ("coremod", r"\b(?:IFMLLoadingPlugin|IClassTransformer|FMLCorePlugin|FMLAT|TweakClass|org\.objectweb\.asm|launchwrapper)\b",
     "Redesign bytecode patches, coremods and access transformers for the target; do not carry them over blindly."),
]
RULES = [(area, re.compile(pattern), advice) for area, pattern, advice in RULES]
COREMOD_RULE = next(rule for rule in RULES if rule[0] == "coremod")
RELEASE = r"1\.[0-9]+(?:\.[0-9]+)?"
BUILD_VERSION = re.compile(rf"(?m)^\s*version[ \t]*=[ \t]*['\"]({RELEASE})-\d+\.\d+\.\d+\.\d+(?:-[^'\"\n]+)?['\"]")
PROPERTY_VERSION = re.compile(rf"(?m)^[ \t]*(?:minecraft_version|mc_version)[ \t]*=[ \t]*['\"]?({RELEASE})['\"]?[ \t]*(?:(?:#|//)[^\n]*)?$")
FILENAME_VERSION = re.compile(r"(?<![\d.])(" + "|".join(map(re.escape, SOURCE_VERSIONS)) + r")(?![\d.])")
JAVA_COMMENTS_OR_LITERAL = re.compile(r'''//[^\r\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*' ''', re.VERBOSE)
LEGACY_MOD = re.compile(r'@(?:cpw\.mods\.fml\.common\.)?Mod\s*\(((?:"(?:\\.|[^"\\])*"|[^()"])*?)\)', re.DOTALL)
ITEM_CALL = re.compile(r'\bGameRegistry\s*\.\s*registerItem\s*\(\s*[\w$.]+\s*,\s*"([a-z0-9_.-]+)"\s*\)')


@dataclass
class ModMetadata:
    origin: str
    mod_id: str = ""
    name: str = ""
    version: str = ""
    minecraft: str = ""
    description: str = ""
    authors: str = ""
    license: str = ""
    dependencies: list[str] = field(default_factory=list)


@dataclass(frozen=True)
class VersionEvidence:
    version: str
    origin: str
    kind: str


@dataclass
class Finding:
    area: str
    advice: str
    matches: int = 0
    examples: list[str] = field(default_factory=list)

    def add(self, location: str) -> None:
        self.matches += 1
        if len(self.examples) < 4:
            self.examples.append(location)


@dataclass(frozen=True)
class ApiHint:
    area: str
    symbol: str
    location: str
    advice: str
    target_types: tuple[str, ...]
    source_versions: tuple[str, ...]


@dataclass
class Audit:
    path: str
    kind: str
    source_version: str | None = None
    target: str = f"Minecraft {MINECRAFT_VERSION} / NeoForge {NEOFORGE_VERSION}"
    java_files: int = 0
    class_files: int = 0
    version_evidence: list[VersionEvidence] = field(default_factory=list)
    mods: list[ModMetadata] = field(default_factory=list)
    findings: list[Finding] = field(default_factory=list)
    api_hints: list[ApiHint] = field(default_factory=list)
    item_candidates: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)


def _string(value: object) -> str:
    # JSON can legally be read by Python with lone surrogates, but TOML cannot contain them.
    return re.sub(r"[\ud800-\udfff]", "\ufffd", value).strip() if isinstance(value, str) else ""


def _read_metadata(text: str, origin: str) -> list[ModMetadata]:
    try:
        data = json.loads(text)
    except (ValueError, RecursionError) as exc:
        raise InputError(f"invalid mcmod.info JSON: {exc}") from exc
    if isinstance(data, dict):
        data = data.get("modList", [data])
    if not isinstance(data, list) or not all(isinstance(row, dict) for row in data):
        raise InputError("expected a mod list, a modList object, or a single mod object in mcmod.info")

    result = []
    for row in data:
        authors = row.get("authorList", row.get("authors"))
        if isinstance(authors, list):
            authors = ", ".join(filter(None, map(_string, authors)))
        dependencies = [
            f"{key}: {json.dumps(row[key], ensure_ascii=True)}"
            for key in ("requiredMods", "dependencies", "dependants") if row.get(key)
        ]
        result.append(ModMetadata(origin, _string(row.get("modid") or row.get("modId")),
                                  _string(row.get("name")), _string(row.get("version")),
                                  _string(row.get("mcversion")), _string(row.get("description")),
                                  _string(authors), _string(row.get("license")), dependencies))
    return result


def _without_java_comments(text: str) -> str:
    return JAVA_COMMENTS_OR_LITERAL.sub(
        lambda match: " " + "\n" * match[0].count("\n") if match[0].startswith("/") else match[0], text
    )


def _literal_option(annotation: str, name: str) -> str:
    match = re.search(rf'(?:^|,)\s*{name}\s*=\s*"([^"\\]*)"', annotation)
    return match[1].strip() if match else ""


def _add_annotation(values: dict[str, str], origin: str,
                    annotated: dict[str, ModMetadata], evidence: list[VersionEvidence]) -> None:
    accepted = _string(values.get("acceptedMinecraftVersions"))
    exact = re.fullmatch(rf"(?:\[({RELEASE})\]|({RELEASE}))", accepted)
    if exact:
        evidence.append(VersionEvidence(exact[1] or exact[2], f"{origin} (@Mod)", "declared"))
    mod_id = _string(values.get("modid"))
    if mod_id:
        mod = annotated.setdefault(mod_id, ModMetadata(origin, mod_id, _string(values.get("name")),
                                                       _string(values.get("version"))))
        if dependency := _string(values.get("dependencies")):
            mod.dependencies.append(f"@Mod dependencies: {dependency}")


def audit(source: ModInput, source_version: str | None = None, *,
          binary_annotations: Iterable[tuple[str, dict[str, str]]] = (), filename: str | None = None) -> Audit:
    result = Audit(str(source.path), "archive" if source.archive else "directory", warnings=source.warnings.copy())
    findings = {area: Finding(area, advice) for area, _, advice in RULES}
    annotation_mods = {}
    for origin, options in binary_annotations:
        _add_annotation(options, origin, annotation_mods, result.version_evidence)
    api_symbols: dict[Symbol, str] = {}
    item_candidates = set()
    for path in source.files:
        name = PurePosixPath(path).name
        if name.endswith(".class"):
            result.class_files += 1
        if name.endswith(".java"):
            try:
                code = _without_java_comments(source.text(path))
            except InputError as exc:
                result.warnings.append(f"Skipped {path!r}: {exc}.")
                continue
            result.java_files += 1
            for annotation in LEGACY_MOD.findall(code):
                options = {key: _literal_option(annotation, key) for key in (
                    "modid", "name", "version", "acceptedMinecraftVersions", "dependencies")}
                _add_annotation(options, path, annotation_mods, result.version_evidence)
            item_candidates.update(ITEM_CALL.findall(code))
            imports: dict[str, str] = {}
            for line_number, line in enumerate(code.splitlines(), 1):
                for symbol in source_symbols(line, imports):
                    if rules_for(symbol, None):
                        api_symbols.setdefault(symbol, f"{path}:{line_number}")
                for area, pattern, _ in RULES:
                    if pattern.search(line):
                        findings[area].add(f"{path}:{line_number}")
            continue
        if name.lower() not in {"mcmod.info", "manifest.mf", "build.gradle", "gradle.properties"}:
            continue
        try:
            text = source.text(path)
            if name.lower() == "mcmod.info":
                mods = _read_metadata(text, path)
                result.mods.extend(mods)
                for mod in mods:
                    if re.fullmatch(RELEASE, mod.minecraft):
                        result.version_evidence.append(VersionEvidence(mod.minecraft, path, "declared"))
            elif name.lower() == "manifest.mf":
                for attribute in re.findall(r"(?im)^(FMLCorePlugin|FMLAT|TweakClass):", text):
                    findings[COREMOD_RULE[0]].add(f"{path} ({attribute})")
            else:
                pattern = BUILD_VERSION if name.lower() == "build.gradle" else PROPERTY_VERSION
                for version in pattern.findall(text.replace("\r\n", "\n")):
                    result.version_evidence.append(VersionEvidence(version, path, "declared"))
        except InputError as exc:
            result.warnings.append(f"Skipped {path!r}: {exc}.")

    known_ids = {mod.mod_id for mod in result.mods if mod.mod_id}
    for mod_id, metadata in annotation_mods.items():
        if mod_id not in known_ids:
            result.mods.append(metadata)
        else:
            for mod in result.mods:
                if mod.mod_id == mod_id:
                    mod.dependencies.extend(metadata.dependencies)

    declared = {e.version for e in result.version_evidence}
    name = filename if filename is not None else source.path.name
    hint = PurePosixPath(name).stem if source.archive else name
    for version in sorted(set(FILENAME_VERSION.findall(hint))):
        result.version_evidence.append(VersionEvidence(version, name, "filename hint"))
    if source_version is not None:
        if source_version not in SOURCE_VERSIONS:
            raise InputError(f"Unsupported source version: {source_version}")
        result.source_version = source_version
        result.version_evidence.append(VersionEvidence(source_version, "--source-version", "override"))
        if declared - {source_version}:
            result.warnings.append("--source-version overrides conflicting declared versions: " + ", ".join(sorted(declared)) + ".")
    elif len(declared) == 1:
        result.source_version = next(iter(declared))
    elif declared:
        result.warnings.append("Conflicting declared Minecraft versions: " + ", ".join(sorted(declared)) + "; verify before selecting one.")
    else:
        result.warnings.append("No exact Minecraft version declared. Filename hints are not enough; verify and use --source-version.")
    if result.source_version and result.source_version not in SOURCE_VERSIONS:
        result.warnings.append(f"{result.source_version} is outside the supported source versions.")
    if not result.java_files:
        result.warnings.append("No readable Java source found. This input can be inspected, but not ported directly.")
    result.findings = [finding for finding in findings.values() if finding.matches]
    for symbol, location in api_symbols.items():
        spelling = symbol.owner + (f"#{symbol.name}" if symbol.name else "")
        result.api_hints.extend(ApiHint(rule.area, spelling, location, rule.advice, rule.alternatives, rule.versions)
                                for rule in rules_for(symbol, result.source_version))
    result.item_candidates = sorted(item_candidates)
    return result


def render_audit(result: Audit) -> str:
    lines = [f"Input: {result.path!r} ({result.kind})", f"Target: {result.target}",
             f"Source Minecraft: {result.source_version or 'unknown'}",
             f"Readable Java files: {result.java_files} | Compiled class files: {result.class_files}"]
    for evidence in result.version_evidence:
        lines.append(f"  {evidence.version} ({evidence.kind}, {evidence.origin!r})")
    lines.append("\nLegacy mod metadata:")
    for mod in result.mods:
        lines.append(f"  {mod.mod_id or '?'}: {mod.name or '(name unknown)'} ({mod.version or 'version unknown'}) — {mod.origin!r}")
        for dependency in mod.dependencies:
            lines.append(f"    Legacy dependency declaration: {dependency}")
    if not result.mods:
        lines.append("  None found; a mod ID must be supplied when generating a workspace.")
    if result.item_candidates:
        lines.append("\nPossible item IDs (verify before using --plain-item): " + ", ".join(result.item_candidates))
    lines.append("\nPorting review (heuristics, not proof of compatibility):")
    for finding in result.findings:
        lines.append(f"  [{finding.area}] {finding.advice}")
        lines.append(f"    {finding.matches} match(es), e.g. " + ", ".join(repr(example) for example in finding.examples))
    if not result.findings:
        lines.append("  No API patterns identified. This does not establish compatibility.")
    if result.api_hints:
        lines.append("\nVersion-aware API guidance (source references, no resolved overloads):")
        for hint in result.api_hints:
            lines.append(f"  {hint.symbol} at {hint.location!r}: {hint.advice}")
            if hint.target_types:
                lines.append("    1.21.1 types to review: " + ", ".join(hint.target_types))
    for warning in result.warnings:
        lines.append(f"  Warning: {warning}")
    return "\n".join(lines)
