"""Version-aware migration guidance. Destinations are design leads, never blind replacements."""

from dataclasses import dataclass
import re

from . import SOURCE_VERSIONS
from .classfiles import Symbol


FML = "cpw/mods/fml/"
FORGE = "net/minecraftforge/"
NEO = "net/neoforged/neoforge/"
FML_NEW = "net/neoforged/fml/"
MC = "net/minecraft/"
PRE_17 = ("1.5.2", "1.6.4")
REGISTRY = FML + "common/registry/GameRegistry"


@dataclass(frozen=True)
class ApiRule:
    area: str
    owners: tuple[str, ...]
    alternatives: tuple[str, ...]
    advice: str
    members: tuple[str, ...] = ()
    versions: tuple[str, ...] = SOURCE_VERSIONS


RULES = (
    ApiRule("entrypoint", (FML + "common/Mod", FML + "common/Mod$Instance"),
            (FML_NEW + "common/Mod", "net/neoforged/bus/api/IEventBus"),
            "Move entrypoint metadata to neoforge.mods.toml. Rewrite the @Mod arguments and wire a constructor to the mod bus.", ("",)),
    ApiRule("lifecycle", tuple(FML + name for name in (
        "common/Mod$EventHandler", "common/event/FMLPreInitializationEvent", "common/event/FMLInitializationEvent",
        "common/event/FMLPostInitializationEvent")),
        (FML_NEW + "event/lifecycle/FMLCommonSetupEvent",),
        "Split legacy pre/init/post work by task: constructor, registry setup, common setup, or client setup. Do not forward the old events."),
    ApiRule("early_lifecycle", tuple(FML + "common/Mod$" + stage for stage in ("PreInit", "Init", "PostInit")),
            (FML_NEW + "event/lifecycle/FMLCommonSetupEvent",),
            "Replace the old lifecycle annotations with constructor and mod-bus setup. Move registry work out of initialization.", versions=PRE_17),
    ApiRule("registries", (REGISTRY,), (NEO + "registries/DeferredRegister", NEO + "registries/RegisterEvent"),
            "Register factories rather than prebuilt objects. Preserve valid namespaced IDs and review remaps; numeric IDs cannot be reused.",
            ("", "registerItem", "registerBlock", "findItem", "findBlock", "findUniqueIdentifierFor")),
    ApiRule("block_entities", (REGISTRY, MC + "tileentity/TileEntity", MC + "src/TileEntity"),
            (MC + "world/level/block/entity/BlockEntity", MC + "world/level/block/entity/BlockEntityType"),
            "Register BlockEntityType and port creation, ticking, save/load and synchronization separately.",
            ("", "registerTileEntity", "registerTileEntityWithAlternatives", "readFromNBT", "writeToNBT")),
    ApiRule("recipes", (REGISTRY,), (),
            "Recreate recipes under data/<namespace>/recipe or generate them with datagen. Validate ingredients, results, and smelting semantics.",
            ("addRecipe", "addShapelessRecipe", "addSmelting")),
    ApiRule("name_lookup", (FML + "common/registry/LanguageRegistry",), (),
            "Move localizations to assets/<namespace>/lang/<locale>.json and update item/block translation keys."),
    ApiRule("legacy_packets", tuple(FML + "common/network/" + name for name in ("NetworkMod", "IPacketHandler", "PacketDispatcher"))
            + (MC + "network/packet/Packet250CustomPayload", MC + "src/Packet250CustomPayload"),
            (NEO + "network/event/RegisterPayloadHandlersEvent", MC + "network/protocol/common/custom/CustomPacketPayload",
             MC + "network/codec/StreamCodec"),
            "Design typed payloads with codecs and explicit packet directions. Recheck handling threads, validation, and send targets.", versions=PRE_17),
    ApiRule("simple_network", tuple(FML + "common/network/simpleimpl/" + name for name in (
        "SimpleNetworkWrapper", "IMessage", "IMessageHandler", "MessageContext")),
        (NEO + "network/event/RegisterPayloadHandlersEvent", NEO + "network/registration/PayloadRegistrar",
         MC + "network/protocol/common/custom/CustomPacketPayload"),
        "Replace message/handler pairs with registered payloads and stream codecs. Recheck client/server direction and thread rules.", versions=("1.7.10",)),
    ApiRule("event_bus", (FML + "common/eventhandler/SubscribeEvent", FORGE + "event/ForgeSubscribe",
                          FORGE + "common/MinecraftForge"),
            ("net/neoforged/bus/api/SubscribeEvent", NEO + "common/NeoForge"),
            "Use the NeoForge game bus for gameplay events and the mod bus for lifecycle/registry events. Recheck event types and cancellation.",
            ("", "EVENT_BUS")),
    ApiRule("client_rendering", (FML + "common/SidedProxy", FML + "client/registry/RenderingRegistry",
                                 FML + "client/registry/ISimpleBlockRenderingHandler", MC + "util/IIcon", MC + "util/Icon"),
            (NEO + "client/event/EntityRenderersEvent",),
            "Isolate client-only code. Use item/block models for simple visuals, and register modern renderer hooks for custom rendering."),
    ApiRule("configuration", (FORGE + "common/Configuration", FORGE + "common/config/Configuration"),
            (NEO + "common/ModConfigSpec", FML_NEW + "ModContainer"),
            "Define and register a ModConfigSpec. Plan a separate legacy config migration if users need existing settings."),
    ApiRule("worldgen", (FML + "common/IWorldGenerator", REGISTRY, MC + "world/gen/feature/WorldGenMinable"),
            (MC + "world/level/levelgen/feature/Feature",),
            "Port generation to configured and placed features and the appropriate biome integration. Verify on fresh worlds.", ("", "registerWorldGenerator")),
    ApiRule("entities", (FML + "common/registry/EntityRegistry",), (MC + "world/entity/EntityType", NEO + "registries/DeferredRegister"),
            "Replace numeric/entity registration with EntityType registration and separately port attributes, spawning, and rendering."),
    ApiRule("block_entities", (MC + "tileentity/TileEntity", MC + "src/TileEntity", REGISTRY),
            (MC + "world/level/block/entity/BlockEntity", MC + "world/level/block/entity/BlockEntityType",
             MC + "world/level/block/entity/BlockEntityTicker"),
            "Register a BlockEntityType, separate logical-server ticking from rendering, and explicitly port serialization. "
            "Legacy numeric IDs and saved NBT are not automatically compatible.",
            ("", "registerTileEntity", "updateEntity", "readFromNBT", "writeToNBT")),
    ApiRule("machine_containers", (MC + "inventory/IInventory", MC + "inventory/ISidedInventory",
                                   MC + "inventory/Container", MC + "client/gui/inventory/GuiContainer"),
            (MC + "world/inventory/AbstractContainerMenu", MC + "world/inventory/MenuType", NEO + "items/IItemHandler"),
            "Expose sided item access, validate actions server-side, and send menu state separately from persistent inventory."),
    ApiRule("universal_electricity", (
                "universalelectricity/api/energy/IEnergyInterface", "universalelectricity/api/energy/IEnergyContainer",
                "universalelectricity/api/energy/EnergyStorageHandler", "calclavia/lib/prefab/tile/TileElectrical",
                "calclavia/lib/prefab/tile/TileElectricalInventory"),
            (NEO + "capabilities/Capabilities$EnergyStorage", NEO + "capabilities/RegisterCapabilitiesEvent",
             NEO + "energy/IEnergyStorage"),
            "Reimplement energy receipt/storage with NeoForge FE capabilities. Port ticking and inventory separately. "
            "No automatic UE↔FE unit conversion or compatibility with an old power network.", versions=("1.6.4",)),
    ApiRule("saved_data", (FORGE + "common/IExtendedEntityProperties", MC + "nbt/NBTTagCompound", MC + "src/NBTTagCompound"),
            (NEO + "attachment/AttachmentType", MC + "nbt/CompoundTag"),
            "Review attachments or item data components and serialization. New APIs are not old-world format converters."),
    ApiRule("numeric_ids", (MC + "block/Block", MC + "item/Item", MC + "src/Block", MC + "src/Item"),
            (NEO + "registries/DeferredRegister", MC + "resources/ResourceLocation"),
            "Replace numeric ID access with named registries. Plan and test data migrations separately.", ("blockID", "itemID"), PRE_17),
    ApiRule("coremods", (FML + "relauncher/IFMLLoadingPlugin", MC + "launchwrapper/IClassTransformer"), (),
            "Review the target code and redesign the patch (prefer supported hooks). Old transformers must not be loaded with NeoForge."),
)

_BY_OWNER: dict[str, list[ApiRule]] = {}
for rule in RULES:
    for owner in rule.owners:
        _BY_OWNER.setdefault(owner, []).append(rule)


def rules_for(symbol: Symbol, version: str | None) -> tuple[ApiRule, ...]:
    rules = _BY_OWNER.get(symbol.owner, ())
    matches = tuple(rule for rule in rules if (version is None or version in rule.versions)
                    and (not rule.members or symbol.name in rule.members))
    # For GameRegistry, a bare type import alone doesn't imply every subsystem's member calls.
    return tuple(rule for rule in matches if symbol.owner != REGISTRY or symbol.name or rule.area == "registries")


def platform_symbol(symbol: Symbol) -> bool:
    return symbol.owner.startswith((FML, FORGE, MC))


IMPORT = re.compile(r"(?m)^\s*import\s+(static\s+)?([\w$]+(?:\.[\w$]+)+)(\.\*)?\s*;")


def source_symbols(line: str, imports: dict[str, str]) -> list[Symbol]:
    """Recognize explicitly imported owners, nested annotations and calls without claiming Java type resolution."""
    match = IMPORT.search(line)
    if match:
        raw = match[2].split(".")
        # Identify the first uppercase type segment so import ...Mod.EventHandler uses JVM `$`.
        split = next((i for i, piece in enumerate(raw) if piece[:1].isupper()), len(raw) - 1)
        tail = raw[split:]
        member = tail.pop() if match[1] and not match[3] and len(tail) > 1 else ""
        owner = "/".join([*raw[:split], "$".join(tail)])
        if not match[1] and not match[3]:
            imports[tail[-1]] = owner
        return [Symbol(owner, member)]
    result = []
    for token, member in re.findall(r"\b([A-Z][A-Za-z0-9_$]*)\s*\.\s*([A-Za-z_$][A-Za-z0-9_$]*)", line):
        if owner := imports.get(token):
            result.extend((Symbol(owner, member), Symbol(owner + "$" + member)))
    return result
