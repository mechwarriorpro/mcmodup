"""Native reactor/projector sources for the two fingerprinted Minechem builds."""

from collections import Counter
from math import prod
import json

from .catalogs import CatalogEntry
from .inputs import InputError
from .port import TEMPLATES


RESOURCE = "src/main/resources/"
BLUEPRINT_TRACE = TEMPLATES / "minechem/reactor-blueprints.txt"
FEATURES = {
    "Fission projector slice order, safe completion, and original fission rule": "fissionprojectionandreaction",
    "Fusion assembly, original fuel/power boundary, and saved inventory": "fusionstructureandrules",
    "Survival lens, blueprint and reactor casing crafts resolve to this release": "reactorcraftchain",
}
LIMITS = (
    "Unmapped recipes, fluid reactions and third-party fluids, full radiation parity, other machines, legacy integrations and saves are incomplete.",
    "Native chemistry uses reviewed flattened materials, saved treatment components and NeoForge effect hooks. Rates, power and tank behavior are release-local; stochastic products are not guaranteed.",
    "Reactor projector, menus and electrical proxies are native adapters. Original projector model animation, GUI tabs and cross-mod integration are not verified.",
    "When both releases are loaded, .394 uses a blank map and glass panes for otherwise identical blueprint and lens recipes. Native cores refuse occupied manager positions; outputs and durability use native limits.",
)


def asset_paths(namespace: str, release_394: bool) -> dict[str, str]:
    source = "assets/minechem/"
    target = f"assets/{namespace}/"
    pairs = {f"textures/blocks/{old}.png": f"textures/block/{new}.png" for old, new in (
        ("blockBlueprintProjector", "blueprint_projector"), ("fusion1", "reactor_casing"), ("fusion2", "reactor_coil"),
        ("default", "reactor_core"), ("blueprint1", "ghost_casing"), ("blueprint2", "ghost_coil"),
    )}
    pairs.update({f"textures/items/{old}.png": f"textures/item/{new}.png" for old, new in (
        ("Blueprint", "reactor_blueprint"), ("lens1", "concave_lens"), ("lens2", "convex_lens"),
        ("lens3", "microscope_lens"), ("lens4", "projector_lens"),
    )})
    pairs.update({f"textures/gui/{old}.png": f"textures/gui/reactor_{new}.png" for old, new in (
        ("ProjectorGUI", "projector"), ("FissionGUI", "fission"), ("FusionChamberGUI", "fusion"),
    )})
    pairs["sounds/projector.ogg" if release_394 else "sound/minechem/projector.ogg"] = "sounds/projector.ogg"
    return {source + old: target + new for old, new in pairs.items()}


def _blueprint_replacements() -> dict[str, str]:
    replacements = {}
    for line in BLUEPRINT_TRACE.read_text(encoding="utf-8").splitlines():
        kind, size, core, counts, grid = line.split()
        bounds = tuple(map(int, size.split(",")))
        center = tuple(map(int, core.split(",")))
        materials = Counter(grid)
        if (kind not in {"fusion", "fission"} or len(bounds) != 3 or len(center) != 3 or
                bounds[0] != bounds[2] or any(n < 1 or n > 13 for n in bounds) or
                any(not 0 <= c < n for n, c in zip(bounds, center)) or
                len(grid) != prod(bounds) or materials.keys() - {".", "0", "A", "C"} or
                counts != f"{materials['A']},{materials['C']}" or f"{kind.upper()}_GRID" in replacements):
            raise InputError(f"Invalid pinned Minechem blueprint: {kind!r}")
        replacements.update({f"{kind.upper()}_SIZE": size, f"{kind.upper()}_CORE": core, f"{kind.upper()}_GRID": grid})
    if len(replacements) != 6:
        raise InputError("Both pinned Minechem reactor blueprints are required")
    return replacements


def _render(template: str, **replacements: str) -> str:
    text = (TEMPLATES / f"minechem/{template}.java").read_text(encoding="utf-8")
    for key, value in replacements.items():
        text = text.replace(f"@{key}@", value)
    return text


def checks(package: str, test_namespace: str, namespace: str, release_394: bool, name: str = "ReactorChecks") -> str:
    return _render("ReactorChecks", PACKAGE=package, TEST_NAMESPACE=test_namespace, NAMESPACE=namespace,
                   RELEASE394=str(release_394).lower(), CLASS=name, **_blueprint_replacements())


def add_project(files: dict[str, str | bytes], namespace: str, package: str, entries: list[CatalogEntry], *, release_394: bool) -> None:
    """Add mechanics to an exact paired project, not the generic catalog converter."""
    elements = sorted((entry for entry in entries if "atomic_number" in entry.legacy), key=lambda entry: entry.legacy["atomic_number"])
    if len(elements) < 113 or [e.legacy["atomic_number"] for e in elements] != list(range(1, len(elements) + 1)):
        raise InputError("Minechem reactors need an exact, contiguous element catalog")
    java = f"src/main/java/{package.replace('.', '/')}"
    for name in ("ReactorBlueprints", "ReactorMachines", "ReactorMenu", "ReactorScreens"):
        files[f"{java}/{name}.java"] = _render(name, PACKAGE=package, RELEASE394=str(release_394).lower(),
                 ELEMENTS=", ".join(json.dumps(e.path) for e in elements), **_blueprint_replacements())

    assets, data = f"{RESOURCE}assets/{namespace}", f"{RESOURCE}data/{namespace}"
    lang = f"{assets}/lang/en_us.json"
    strings = json.loads(files[lang])

    def put(path: str, value: object) -> None:
        files[path] = json.dumps(value, indent=2) + "\n"

    blocks = {"blueprint_projector": "Blueprint Projector", "reactor_casing": "Reactor Casing", "reactor_coil": "Reactor Coil",
              "fusion_core": "Fusion Chamber", "fission_core": "Fission Chamber",
              "ghost_casing": "Projected Casing", "ghost_coil": "Projected Coil"}
    placeable = ("blueprint_projector", "reactor_casing", "reactor_coil")
    for key, label in blocks.items():
        strings[f"block.{namespace}.{key}"] = label
        model = f"{namespace}:block/{key}"
        texture = "reactor_core" if key.endswith("_core") else key
        content = {"parent": "minecraft:block/cube_all", "textures": {"all": f"{namespace}:block/{texture}"}}
        if key.startswith("ghost_"):
            content["render_type"] = "minecraft:translucent"
        put(f"{assets}/models/block/{key}.json", content)
        put(f"{assets}/blockstates/{key}.json", {"variants": {"": {"model": model}}})
        if key in placeable:
            put(f"{assets}/models/item/{key}.json", {"parent": model})
            put(f"{data}/loot_table/blocks/{key}.json", {"type": "minecraft:block", "pools": [{"rolls": 1,
                "conditions": [{"condition": "minecraft:survives_explosion"}], "entries": [{"type": "minecraft:item", "name": f"{namespace}:{key}"}]}]})
    pickaxe = f"{RESOURCE}data/minecraft/tags/block/mineable/pickaxe.json"
    tag = json.loads(files[pickaxe])
    tag["values"].extend(f"{namespace}:{key}" for key in blocks if not key.startswith("ghost_"))
    put(pickaxe, tag)

    items = {"fusion_blueprint": "Fusion Blueprint", "fission_blueprint": "Fission Blueprint", "concave_lens": "Concave Lens",
             "convex_lens": "Convex Lens", "microscope_lens": "Microscope Lens", "projector_lens": "Projector Lens"}
    if not release_394:
        items["fusion_star"] = "Fusion Star"
    for key, name in items.items():
        strings[f"item.{namespace}.{key}"] = name
        texture = ("minecraft:item/nether_star" if key == "fusion_star" else
                   f"{namespace}:item/reactor_blueprint" if key.endswith("_blueprint") else f"{namespace}:item/{key}")
        put(f"{assets}/models/item/{key}.json", {"parent": "minecraft:item/generated", "textures": {"layer0": texture}})
    strings.update({f"gui.{namespace}.{key}": value for key, value in {
        "reactor_incomplete": "The reactor structure is incomplete.", "reactor_formed": "Structure formed",
        "reactor_waiting": "Site unavailable", "reactor_slice": "Slice %s of %s", "reactor_energy": "%s / %s FE",
    }.items()})
    put(lang, strings)
    put(f"{assets}/sounds.json", {"projector": {"sounds": [f"{namespace}:projector"]}})
    files[f"{data}/mcmodup/reactor-blueprints.txt"] = BLUEPRINT_TRACE.read_bytes()

    def recipe(key: str, content: dict, *, count: int = 1, filename: str | None = None, condition: dict | None = None) -> None:
        put(f"{data}/recipe/{filename or key}.json", content | {"category": "misc", "result": {"id": f"{namespace}:{key}", "count": count}} |
            ({"neoforge:conditions": [condition]} if condition else {}))

    def shaped(key: str, pattern: list[str], bindings: dict[str, str], **options) -> None:
        recipe(key, {"type": "minecraft:crafting_shaped", "pattern": pattern,
                     "key": {symbol: {"item": item} for symbol, item in bindings.items()}}, **options)

    def shapeless(key: str, ingredients: list[str], **options) -> None:
        recipe(key, {"type": "minecraft:crafting_shapeless", "ingredients": [{"item": item} for item in ingredients]}, **options)

    final_loaded = {"type": "neoforge:mod_loaded", "modid": "minechem_chemistry"}
    legacy_recipe = {"type": "neoforge:not", "value": final_loaded} if release_394 else None
    for name, shape in (("concave_lens", ["G G", "GGG", "G G"]), ("convex_lens", [" G ", "GGG", " G "])):
        shaped(name, shape, {"G": "minecraft:glass"}, condition=legacy_recipe)
        if release_394:
            shaped(name, shape, {"G": "minecraft:glass_pane"}, filename=f"{name}_paired", condition=final_loaded)
    for name, gem in (("fusion_blueprint", "minecraft:diamond_block"), ("fission_blueprint", "minecraft:diamond")):
        shapeless(name, ["minecraft:paper", gem], condition=legacy_recipe)
        if release_394:
            shapeless(name, ["minecraft:map", gem], filename=f"{name}_paired", condition=final_loaded)
    concave, convex = f"{namespace}:concave_lens", f"{namespace}:convex_lens"
    shaped("microscope_lens", ["A", "B", "A"], {"A": convex, "B": concave})
    shaped("projector_lens", ["ABA"], {"A": concave, "B": convex})
    iron = "minecraft:iron_ingot"
    shaped("blueprint_projector", [" I ", "GPL", " I "], {"I": iron, "G": "minecraft:redstone_lamp", "P": "minecraft:glass_pane",
                                                          "L": f"{namespace}:projector_lens"})
    shaped("reactor_casing", ["ILI", "ILI", "ILI"], {"I": iron, "L": f"{namespace}:element_pb"}, count=16)
    shaped("reactor_coil", ["IWI", "IBI", "IWI"], {"I": iron, "W": f"{namespace}:element_w", "B": f"{namespace}:element_be"}, count=16)
    # Restore the actual microscope progression for the linked releases only.
    shaped("microscope", [" LI", " PI", "III"], {"L": f"{namespace}:microscope_lens", "P": "minecraft:glass_pane", "I": iron})
