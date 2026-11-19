"""Generate NeoForge machine logic and a recipe table for the recovered Minechem catalog."""

from dataclasses import dataclass
from enum import StrEnum
import json
from pathlib import Path
import re

from .catalogs import CatalogEntry
from .inputs import InputError


TEMPLATES = Path(__file__).with_name("templates") / "minechem"
FINAL_LEGACY_SHA256 = "2443c7f91351f0e6c2323636be28a6fe8697704f20edf378dfe1a9d7a3070bea"
JAVA_CLASSES = ("ChemistryMachines", "ChemistryMenu", "ChemistryScreen", "ChemistryRecipes",
                "ChemistryResearch", "ResearchMenus", "ResearchScreens")
# The exact original can choose either output for the same ghost pattern across fresh boots.
SYNTHESIS_394_COLLISION = ("m140", "m143")
SYNTHESIS_394_CLASSES = ("Synthesizer394", "Synthesis394Menu", "Synthesis394Screen", "Synthesis394Rules")


class DecomposerMode(StrEnum):
    BATCH = "batch-adaptation"
    FINAL = "5.0.5-final-output-buffer"
    RELEASE_394 = "5.0.5.394-prepaid-output-buffer"


def machine_classes(mode: DecomposerMode) -> tuple[str, ...]:
    return JAVA_CLASSES + SYNTHESIS_394_CLASSES if mode == DecomposerMode.RELEASE_394 else JAVA_CLASSES


@dataclass(frozen=True)
class MachineRecipe:
    machine: str
    id: str
    inputs: tuple[tuple[str, int], ...]
    outputs: tuple[tuple[str, int], ...]
    energy: int
    ticks: int
    source: str
    fluid_input: tuple[str, int] | None = None
    ghost_pattern: tuple[tuple[str, int] | None, ...] = ()
    shaped: bool = False

    def describe(self) -> dict:
        description = {"machine": self.machine, "id": self.id, "energy_fe": self.energy, "ticks": self.ticks,
                       "inputs": [{"id": name, "count": count} for name, count in self.inputs],
                       "outputs": [{"id": name, "count": count} for name, count in self.outputs],
                       "legacy_source": self.source}
        if self.fluid_input:
            name, volume = self.fluid_input
            description["fluid_input"] = {"id": name, "millibuckets": volume}
        if self.ghost_pattern:
            description.update({"shaped": self.shaped, "consumption": "on_extraction", "ghost_pattern": [
                {"id": part[0], "count": part[1]} if part else None for part in self.ghost_pattern]})
        return description


def _decomposition(source_item: str, products: tuple[tuple[str, int], ...], source: str) -> MachineRecipe:
    # One unit per output. The exact FINAL build pays per emitted item while a
    # positive charge remains; generic conversions reserve this amount as a batch.
    amount = sum(count for _, count in products)
    return MachineRecipe("decomposer", source_item, ((source_item, 1),), products, amount, amount, source)


def release394_trace() -> dict[str, tuple[tuple[str, int], ...]]:
    """Read bounded observation data, not legacy recipe code or reconstructed formulas."""
    path = TEMPLATES / "release394-decomposition.txt"
    rows = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.startswith("#") or not line:
            continue
        key, sep, values = line.partition("=")
        if not sep or key in rows or not re.fullmatch(r"[a-z][a-z0-9_]*", key):
            raise InputError(f"Invalid .394 trace input {key!r}")
        parts = []
        for value in values.split(","):
            match = re.fullmatch(r"([em](?:0|[1-9][0-9]{0,3}))x([1-9][0-9]{0,3})", value)
            if not match:
                raise InputError(f"Invalid .394 trace output for {key}: {value!r}")
            part, count = match.groups()
            parts.append((part, int(count)))
        if sum(count for _, count in parts) > 4096:
            raise InputError(f".394 trace exceeds output limit for {key}")
        rows[key] = tuple(parts)
    return rows


def release394_synthesis_trace() -> dict[str, tuple[int, int, bool, tuple[tuple[str, int] | None, ...]]]:
    """Read successful original extractions and the ghost slots that selected them."""
    rows = {}
    for line in (TEMPLATES / "release394-synthesis.txt").read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        key, _, data = line.partition("=")
        match = re.fullmatch(r"([1-9]\d{0,5});([1-9]\d?);(shaped|shapeless);(.+)", data)
        if key in rows or not re.fullmatch(r"(?:m[0-9]+|[a-z][a-z_]+)", key) or match is None:
            raise InputError(f"Invalid .394 synthesis trace row: {line!r}")
        cost, result, mode, pattern = match.groups()
        parts = []
        for token in pattern.split(","):
            if token == "_":
                parts.append(None)
                continue
            part = re.fullmatch(r"([em]\d{1,3})x([1-9]\d{0,2})", token)
            if part is None:
                raise InputError(f"Invalid .394 synthesis ingredient for {key}: {token!r}")
            parts.append((part[1], int(part[2])))
        if len(parts) != 9 or not any(parts) or int(cost) > 100_000 or int(result) > 64:
            raise InputError(f"Unrepresentable .394 synthesis rule for {key}")
        rows[key] = (int(cost), int(result), mode == "shaped", tuple(parts))
    if not rows:
        raise InputError("The .394 synthesis trace is empty")
    return rows


def _release394_recipes(namespace: str, entries: list[CatalogEntry]) -> list[MachineRecipe]:
    traced = release394_trace()
    ids = {}
    for entry in entries:
        kind = "e" if entry.path.startswith("element_") else "m"
        metadata = entry.legacy.get("legacy_metadata")
        key = f"{kind}{metadata}"
        if not isinstance(metadata, int) or key in ids:
            raise InputError(f"Missing or duplicate .394 metadata for {entry.path}")
        ids[key] = f"{namespace}:{entry.path}"
    if {key for key in ids if key.startswith("m")} != {key for key in traced if re.fullmatch(r"m\d+", key)}:
        raise InputError("The .394 molecule catalog does not match the measured decomposition inputs.")

    def resolve(key: str) -> str:
        if key not in ids:
            raise InputError(f"Could not map original .394 chemical metadata {key}.")
        return ids[key]

    def result_id(key: str) -> str:
        return resolve(key) if re.fullmatch(r"m\d+", key) else f"minecraft:{key}"

    recipes = []
    for key, parts in traced.items():
        fluid = key == "fluid_water"
        item = result_id("water" if fluid else key)
        products = tuple((resolve(part), count) for part, count in parts)
        recipes.append(MachineRecipe("decomposer", item, () if fluid else ((item, 1),), products, 1_000,
                                     sum(count for _, count in products), "5.0.5.394 Forge 10.13.2.1291 tick trace",
                                     (item, 1000) if fluid else None))
    for key, (cost, result, shaped, pattern) in release394_synthesis_trace().items():
        if key in SYNTHESIS_394_COLLISION:
            continue
        out = result_id(key)
        ghosts = tuple((resolve(part[0]), part[1]) if part else None for part in pattern)
        recipes.append(MachineRecipe("synthesizer", out, tuple(part for part in ghosts if part), ((out, result),),
                                     cost, 0, "5.0.5.394 Forge 10.13.2.1291 extraction trace",
                                     ghost_pattern=ghosts, shaped=shaped))
    return recipes


def machine_recipes(namespace: str, entries: list[CatalogEntry], *,
                    mode: DecomposerMode = DecomposerMode.BATCH) -> list[MachineRecipe]:
    if mode == DecomposerMode.RELEASE_394:
        return _release394_recipes(namespace, entries)
    recipes = []
    for entry in sorted(entries, key=lambda item: item.path):
        if not entry.components:
            continue
        compound = f"{namespace}:{entry.path}"
        parts = tuple((f"{namespace}:{item}", count) for item, count in entry.components)
        if sum((count + 63) // 64 for _, count in parts) > 18:
            raise InputError(f"Recipe for {compound} does not fit the machine's 18 inventory slots.")
        recipes.extend((
            MachineRecipe("synthesizer", compound, parts, ((compound, 1),), 500, 20, "MoleculeEnum"),
            _decomposition(compound, parts, "MoleculeEnum"),
        ))
    # Reviewed deterministic 5.0.5 FINAL rules from registerVanillaChemicalRecipes. This is
    # not a generic evaluator for the old registry method or the pack's config/ore recipes.
    catalog = {entry.path for entry in entries}
    for vanilla, path, amount in (("water_bucket", "molecule_water", 16),
                                  ("iron_ingot", "element_fe", 16), ("gold_ingot", "element_au", 16)):
        if path in catalog:
            original, chemical = f"minecraft:{vanilla}", f"{namespace}:{path}"
            recipes.append(_decomposition(original, ((chemical, amount),), "MinechemRecipes (reviewed starter mapping)"))
            if vanilla != "water_bucket":
                recipes.append(MachineRecipe("synthesizer", original, ((chemical, amount),), ((original, 1),),
                                             10_000, 20, "MinechemRecipes (reviewed starter mapping)"))
    return recipes


def _java_recipes(package: str, namespace: str, entries: list[CatalogEntry], recipes: list[MachineRecipe]) -> str:
    def parts(values: tuple[tuple[str, int], ...]) -> str:
        return "List.of(" + ", ".join(f'new Part("{item}", {count})' for item, count in values) + ")"

    def rows(machine: str, *, fluid: bool = False) -> str:
        return ",\n".join(f'        new Recipe("{r.id}", {r.energy}, {r.ticks}, {r.fluid_input[1] if r.fluid_input else 0}, '
                           f'{parts(r.inputs)}, {parts(r.outputs)})' for r in recipes
                           if r.machine == machine and not r.ghost_pattern and bool(r.fluid_input) == fluid)

    if any(recipe.ghost_pattern for recipe in recipes):
        # The .394 inspector reads the same verified rule table as the ghost-grid machine.
        synthesis = """Synthesis394Rules.ALL.stream().map(row -> new Recipe(row.result().id(), row.cost(), 0, 0,
        row.pattern().stream().filter(part -> part.count() > 0).map(part -> new Part(part.id(), part.count())).toList(),
        List.of(new Part(row.result().id(), row.result().count())))).toList()"""
    else:
        synthesis = f"List.of(\n{rows('synthesizer')}\n    )"
    chemical_ids = ",\n".join(f'        "{namespace}:{entry.path}"' for entry in entries)
    return f'''package {package};

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

final class ChemistryRecipes {{
    record Part(String id, int count) {{
        Item item() {{ return BuiltInRegistries.ITEM.get(ResourceLocation.parse(id)); }}
    }}

    record Recipe(String id, int energy, int ticks, int fluidAmount, List<Part> inputs, List<Part> outputs) {{}}

    static final Set<String> CHEMICALS = Set.of(
{chemical_ids}
    );
    static final List<Recipe> SYNTHESIZE = {synthesis};
    static final Map<String, Recipe> BY_RESULT = index(SYNTHESIZE);
    static final Map<String, Recipe> DECOMPOSE = index(List.of(
{rows('decomposer')}
    ));
    static final Map<String, Recipe> DECOMPOSE_FLUID = index(List.of(
{rows('decomposer', fluid=True)}
    ));

    private static Map<String, Recipe> index(List<Recipe> recipes) {{
        return recipes.stream().collect(Collectors.toUnmodifiableMap(Recipe::id, Function.identity()));
    }}

    static int indexOf(String id) {{
        for (int i = 0; i < SYNTHESIZE.size(); i++) if (SYNTHESIZE.get(i).id().equals(id)) return i;
        return 0;
    }}
}}
'''


def machine_project(namespace: str, package: str, entries: list[CatalogEntry], recipes: list[MachineRecipe], *,
                    mode: DecomposerMode, linked_decomposer: bool = False) -> tuple[dict[str, str], dict[str, str]]:
    def document(value: dict) -> str:
        return json.dumps(value, indent=2) + "\n"

    java = "src/main/java/" + package.replace(".", "/")
    source = "src/main/resources"
    assets = f"{source}/assets/{namespace}"
    data = f"{source}/data"
    prepaid = mode == DecomposerMode.RELEASE_394
    if linked_decomposer and mode == DecomposerMode.BATCH:
        raise InputError("Linked decomposer methods require an exact Minechem release")
    bridge = f"mcmodup_kernel.{namespace}.DecomposerAccess"
    jam_test = f"{bridge}.canUnjam(outputs)" if linked_decomposer and not prepaid else "hasEmptyFinalOutput()"
    compact = f"{bridge}.compact(outputs)" if linked_decomposer and prepaid else "compactPrepaidOutputs()"
    files = {f"{java}/{name}.java": (TEMPLATES / f"{name}.java").read_text(encoding="utf-8")
             .replace("@PACKAGE@", package).replace("@NAMESPACE@", namespace)
             .replace("@BUFFERED_DECOMPOSER@", str(mode != DecomposerMode.BATCH).lower())
             .replace("@PREPAID_DECOMPOSER@", str(prepaid).lower())
             .replace("@REGISTER_MACHINES@", ("List.of(decomposer, Synthesizer394.register(bus, items))" if prepaid else
                                               "List.of(items.registerSimpleBlockItem(SYNTHESIZER), decomposer)"))
             .replace("@FINAL_CAN_UNJAM@", jam_test).replace("@COMPACT_PREPAID@", compact)
             for name in machine_classes(mode) if name not in ("ChemistryRecipes", *SYNTHESIS_394_CLASSES)}
    files[f"{java}/ChemistryRecipes.java"] = _java_recipes(package, namespace, entries, recipes)
    if prepaid:
        for name in SYNTHESIS_394_CLASSES:
            code = (TEMPLATES / f"{name}.java").read_text(encoding="utf-8")
            if name == "Synthesis394Rules":
                cases = []
                for row in recipes:
                    if not row.ghost_pattern:
                        continue
                    parts = ", ".join(f'new Part("{part[0]}", {part[1]})' if part else "EMPTY" for part in row.ghost_pattern)
                    cases.append(f'        new Rule(new Part("{row.id}", {row.outputs[0][1]}), {row.energy}, '
                                 f'{str(row.shaped).lower()}, List.of({parts}))')
                code = code.replace("@RULES@", ",\n".join(cases))
            files[f"{java}/{name}.java"] = code.replace("@PACKAGE@", package).replace("@NAMESPACE@", namespace)
    locales = {f"gui.{namespace}.{key}": label for key, label in {
        "process": "Process", "auto": "Auto", "manual": "Manual",
        "clear_ghosts": "Clear", "ghost_help": "Template slots: copy the held count with left-click, adjust with right-click.",
        "synthesis_cost": "Batch: %s FE", "synthesis_power": "Stored: %s FE",
        "auto_help": "Toggle repeated processing using the current recipe, input and FE",
        "decompose": "Insert one material to split", "charge": "Optional fuel: Redstone Dust (+500 FE when needed)",
        "water_tank": "Water: %s/%s mB", "empty_tank": "Empty tank",
        "requirement": "%s × %s", "recipe_help": "Recipe ingredients (hold Shift to skip 10 recipes)",
        "state_0": "Ready · %s FE", "state_1": "Working · %s FE", "state_2": "Need input · %s FE",
        "state_3": "Need power · %s FE", "state_4": "Output full · %s FE", "state_5": "Done · %s FE",
        "inspect_sample": "Insert an item to inspect", "inspect_book": "Insert a journal to record the recipe",
        "inspect_synthesis": "Synthesis", "inspect_decomposition": "Decomposition",
        "inspect_inputs": "Ingredients · %s FE", "inspect_outputs": "Products · %s FE",
        "no_recipe": "No supported recipe in this mode",
        "journal_owner": "Last used by %s", "journal_count": "Discovered recipes: %s", "journal_active": "Active: %s",
        "journal_empty": "Discover recipes in a Microscope", "journal_choose": "Choose a discovered recipe",
        "journal_previous": "Select previous discovery", "journal_next": "Select next discovery",
        "load_journal": "Insert a journal to load its active synthesis recipe",
    }.items()}
    locales[f"item.{namespace}.chemist_journal"] = "Chemist's Journal"
    files[f"{assets}/models/item/chemist_journal.json"] = document({
        "parent": "minecraft:item/generated", "textures": {"layer0": "minecraft:item/writable_book"},
    })
    # Both originals use a book and glass. The pane variant prevents the .394
    # preview from capturing FINAL's craft when the two are installed together.
    files[f"{data}/{namespace}/recipe/chemist_journal.json"] = document({
        "type": "minecraft:crafting_shapeless", "category": "misc",
        "ingredients": [{"item": "minecraft:book"}, {"item": "minecraft:glass_pane" if prepaid else "minecraft:glass"}],
        "result": {"id": f"{namespace}:chemist_journal", "count": 1},
    })
    blocks = []
    for block, name, texture, pattern, keys in (
        ("synthesizer", "Chemical Synthesizer", "minecraft:block/lapis_block", ["IRI", "IPI", "IDI"],
         {"I": "iron_ingot", "R": "redstone", "P": "piston", "D": "diamond"}),
        ("decomposer", "Chemical Decomposer", "minecraft:block/iron_block", ["III", "IPI", "IRI"],
         {"I": "iron_ingot", "R": "redstone", "P": "piston"}),
        # A spyglass stands in for the legacy lens chain in this partial profile.
        ("microscope", "Microscope", "minecraft:block/polished_andesite", [" LI", " PI", "III"],
         {"I": "iron_ingot", "L": "spyglass", "P": "glass_pane"}),
    ):
        blocks.append(block)
        model = f"{namespace}:block/{block}"
        identifier = f"{namespace}:{block}"
        locales[f"block.{namespace}.{block}"] = name
        files[f"{assets}/blockstates/{block}.json"] = document({"variants": {"": {"model": model}}})
        files[f"{assets}/models/block/{block}.json"] = document({"parent": "minecraft:block/cube_all", "textures": {"all": texture}})
        files[f"{assets}/models/item/{block}.json"] = document({"parent": model})
        files[f"{data}/{namespace}/loot_table/blocks/{block}.json"] = document({
            "type": "minecraft:block", "pools": [{"rolls": 1, "conditions": [{"condition": "minecraft:survives_explosion"}],
                                                  "entries": [{"type": "minecraft:item", "name": identifier}]}],
        })
        # Separate the two native recipes when both releases are loaded. These
        # are preview recipes, not imports of the original progression.
        if prepaid:
            keys = {**keys, "I": "copper_ingot"}
        # Keep the blocks craftable without the currently unported prerequisites.
        files[f"{data}/{namespace}/recipe/{block}.json"] = document({
            "type": "minecraft:crafting_shaped", "category": "misc", "pattern": pattern,
            "key": {symbol: {"item": f"minecraft:{item}"} for symbol, item in keys.items()},
            "result": {"id": identifier, "count": 1},
        })
    files[f"{data}/minecraft/tags/block/mineable/pickaxe.json"] = document(
        {"replace": False, "values": [f"{namespace}:{block}" for block in blocks]})
    return files, locales
