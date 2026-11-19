"""Generate NeoForge machine logic and a recipe table for the recovered Minechem catalog."""

from dataclasses import dataclass
from enum import StrEnum
import json
from pathlib import Path
import re

from . import minechem_effects, minechem_final_synthesis, minechem_materials
from .catalogs import CatalogEntry
from .inputs import InputError


TEMPLATES = Path(__file__).with_name("templates") / "minechem"
FINAL_LEGACY_SHA256 = "2443c7f91351f0e6c2323636be28a6fe8697704f20edf378dfe1a9d7a3070bea"
JAVA_CLASSES = ("ChemistryMachines", "ChemistryMenu", "ChemistryScreen", "ChemistryRecipes",
                "ChemistryResearch", "ResearchMenus", "ResearchScreens")
# The exact original can choose either output for the same ghost pattern across fresh boots.
SYNTHESIS_394_COLLISION = ("m140", "m143")
SYNTHESIS_FINAL_CLASSES = ("SynthesizerFinal", "SynthesisFinalMenu", "SynthesisFinalScreen", "SynthesisFinalRules")
SYNTHESIS_394_CLASSES = ("Synthesizer394", "Synthesis394Menu", "Synthesis394Screen", "Synthesis394Rules")


class DecomposerMode(StrEnum):
    BATCH = "batch-adaptation"
    FINAL = "5.0.5-final-output-buffer"
    RELEASE_394 = "5.0.5.394-prepaid-output-buffer"


def machine_classes(mode: DecomposerMode) -> tuple[str, ...]:
    research = () if mode == DecomposerMode.BATCH else ("JournalClone", "MaterialRules", "PolytoolItem", *minechem_effects.JAVA)
    synthesis = {DecomposerMode.FINAL: SYNTHESIS_FINAL_CLASSES, DecomposerMode.RELEASE_394: SYNTHESIS_394_CLASSES}.get(mode, ())
    return JAVA_CLASSES + research + synthesis


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
    canonical: str | None = None
    native_output: bool = True
    material_key: str | None = None
    material_node: str | None = None
    declared_slots: int = 0

    def describe(self) -> dict:
        description = {"machine": self.machine, "id": self.id, "energy_fe": self.energy, "ticks": self.ticks,
                       "inputs": [{"id": name, "count": count} for name, count in self.inputs],
                       "outputs": [{"id": name, "count": count} for name, count in self.outputs],
                       "legacy_source": self.source}
        if self.fluid_input:
            name, volume = self.fluid_input
            description["fluid_input"] = {"id": name, "millibuckets": volume}
        if self.material_key is not None:
            description.update(observed_input=self.material_key, material_rule=self.material_node, outputs_are_preview=True)
        if self.ghost_pattern:
            description.update({"shaped": self.shaped, "consumption": "on_extraction", "ghost_pattern": [
                {"id": part[0], "count": part[1]} if part else None for part in self.ghost_pattern]})
        if self.canonical is not None:
            description.update(legacy_canonical=self.canonical, native_output_available=self.native_output)
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


def _exact_materials(namespace: str, entries: list[CatalogEntry], *, prepaid: bool,
                     linked: bool = False) -> tuple[list[MachineRecipe], dict[str, minechem_materials.Node]]:
    ids = minechem_final_synthesis.chemical_ids(namespace, entries)
    fixed = None
    if prepaid:
        traced = release394_trace()
        if {key for key in ids if key.startswith("m")} != {key for key in traced if re.fullmatch(r"m\d+", key)}:
            raise InputError("The .394 molecule catalog does not match the measured decomposition inputs.")
        fixed = {(ids[key] if key in ids else f"minecraft:{key}"): tuple((minechem_materials.chemical_id(part, ids), count)
                 for part, count in parts) for key, parts in traced.items() if key != "fluid_water"}
    materials, nodes = minechem_materials.release(namespace, ids, prepaid=prepaid, paired=linked, ordered=fixed)
    recipes = []
    for row in materials:
        amount = sum(count for _, count in row.representative)
        recipes.append(MachineRecipe("decomposer", row.id, () if row.volume else ((row.id, 1),),
                                     row.representative, 1_000 if prepaid else 1 if row.volume else amount,
                                     amount, "Forge material registry and output trace", (row.id, row.volume) if row.volume else None,
                                     material_key=row.key, material_node=nodes[row.key].encode()))
    return recipes, nodes


def machine_recipes(namespace: str, entries: list[CatalogEntry], *,
                    mode: DecomposerMode = DecomposerMode.BATCH, linked: bool = False) -> list[MachineRecipe]:
    if mode == DecomposerMode.RELEASE_394:
        recipes, _ = _exact_materials(namespace, entries, prepaid=True, linked=linked)
        ids = minechem_final_synthesis.chemical_ids(namespace, entries)
        for out, result, cost, shaped, pattern in minechem_materials.synthesis394(namespace, ids):
            ghosts = pattern + (None,) * (9 - len(pattern))
            recipes.append(MachineRecipe("synthesizer", out, tuple(part for part in ghosts if part), ((out, result),),
                                         cost, 0, "5.0.5.394 Forge 10.13.2.1291 synthesis registry",
                                         ghost_pattern=ghosts, shaped=shaped, declared_slots=len(pattern)))
        return recipes
    if mode == DecomposerMode.FINAL:
        recipes, _ = _exact_materials(namespace, entries, prepaid=False, linked=linked)
        ids = minechem_final_synthesis.chemical_ids(namespace, entries)
        for row in minechem_final_synthesis.observations():
            out, native_output = minechem_final_synthesis.resolve(row.key, namespace, ids)
            ghosts = tuple((minechem_final_synthesis.resolve(part[0], namespace, ids)[0], part[1]) if part else None
                           for part in row.pattern)
            recipes.append(MachineRecipe("synthesizer", out, tuple(part for part in ghosts if part), ((out, row.count),),
                                         row.energy, 0, "5.0.5 FINAL ordered Forge registration/extraction trace",
                                         ghost_pattern=ghosts, shaped=row.shaped, canonical=row.canonical,
                                         native_output=native_output))
        return recipes
    recipes = []
    for entry in sorted(entries, key=lambda item: item.path):
        if not entry.components:
            continue
        compound = f"{namespace}:{entry.path}"
        parts = tuple((f"{namespace}:{item}", count) for item, count in entry.components)
        if sum((count + 63) // 64 for _, count in parts) > 18:
            raise InputError(f"Recipe for {compound} does not fit the machine's 18 inventory slots.")
        if mode == DecomposerMode.BATCH:
            recipes.append(MachineRecipe("synthesizer", compound, parts, ((compound, 1),), 500, 20, "MoleculeEnum"))
        recipes.append(_decomposition(compound, parts, "MoleculeEnum"))
    # Starter mappings for the generic, non-pinned catalog adapter only.
    catalog = {entry.path for entry in entries}
    for vanilla, path, amount in (("water_bucket", "molecule_water", 16),
                                  ("iron_ingot", "element_fe", 16), ("gold_ingot", "element_au", 16)):
        if path in catalog:
            original, chemical = f"minecraft:{vanilla}", f"{namespace}:{path}"
            recipes.append(_decomposition(original, ((chemical, amount),), "MinechemRecipes (reviewed starter mapping)"))
            if mode == DecomposerMode.BATCH and vanilla != "water_bucket":
                recipes.append(MachineRecipe("synthesizer", original, ((chemical, amount),), ((original, 1),),
                                             10_000, 20, "MinechemRecipes (reviewed starter mapping)"))
    return recipes


def _java_recipes(package: str, namespace: str, entries: list[CatalogEntry], recipes: list[MachineRecipe], mode: DecomposerMode) -> str:
    def parts(values: tuple[tuple[str, int], ...]) -> str:
        return "List.of(" + ", ".join(f'new Part("{item}", {count})' for item, count in values) + ")"

    def rows(machine: str, *, fluid: bool = False) -> str:
        return ",\n".join(f'        new Recipe("{r.id}", {r.energy}, {r.ticks}, {r.fluid_input[1] if r.fluid_input else 0}, '
                           f'{parts(r.inputs)}, {parts(r.outputs)})' for r in recipes
                           if r.machine == machine and not r.ghost_pattern and bool(r.fluid_input) == fluid)

    if mode == DecomposerMode.RELEASE_394:
        synthesis = """Synthesis394Rules.ALL.stream().map(row -> new Recipe(row.result().id(), row.cost(), 0, 0,
        row.pattern().stream().filter(part -> part.count() > 0).map(part -> new Part(part.id(), part.count())).toList(),
        List.of(new Part(row.result().id(), row.result().count())))).toList()"""
    elif mode == DecomposerMode.FINAL:
        synthesis = """SynthesisFinalRules.ALL.stream().filter(SynthesisFinalRules.Rule::nativeOutput)
        .map(row -> new Recipe(row.result().id(), row.cost(), 0, 0,
            row.pattern().stream().filter(part -> part.count() > 0).toList(), List.of(row.result()))).toList()"""
    else:
        synthesis = f"List.of(\n{rows('synthesizer')}\n    )"
    decomposition = ("MaterialRules.recipes(false)" if mode != DecomposerMode.BATCH else f"List.of(\n{rows('decomposer')}\n    )")
    fluids = ("MaterialRules.recipes(true)" if mode != DecomposerMode.BATCH else "List.of()")
    roll = "MaterialRules.roll(recipe, random)" if mode != DecomposerMode.BATCH else "recipe.outputs()"
    variable = "MaterialRules.variable(recipe)" if mode != DecomposerMode.BATCH else "false"
    key = "MaterialRules.validInput(stack)" if mode != DecomposerMode.BATCH else "true"
    chemical_ids = ",\n".join(f'        "{namespace}:{entry.path}"' for entry in entries)
    return f'''package {package};

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

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
    static final Map<String, Recipe> DECOMPOSE = index({decomposition});
    static final Map<String, Recipe> DECOMPOSE_FLUID = index({fluids});

    static Recipe input(ItemStack stack) {{
        return {key} ? DECOMPOSE.get(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()) : null;
    }}

    static boolean variable(Recipe recipe) {{ return {variable}; }}

    static List<Part> roll(Recipe recipe, RandomSource random) {{ return {roll}; }}

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
                    mode: DecomposerMode, linked_decomposer: bool = False) -> tuple[dict[str, str | bytes], dict[str, str]]:
    def document(value: dict) -> str:
        return json.dumps(value, indent=2) + "\n"

    java = "src/main/java/" + package.replace(".", "/")
    source = "src/main/resources"
    assets = f"{source}/assets/{namespace}"
    data = f"{source}/data"
    prepaid = mode == DecomposerMode.RELEASE_394
    final = mode == DecomposerMode.FINAL
    clone_journal = mode != DecomposerMode.BATCH
    if linked_decomposer and mode == DecomposerMode.BATCH:
        raise InputError("Linked decomposer methods require an exact Minechem release")
    bridge = f"mcmodup_kernel.{namespace}.DecomposerAccess"
    jam_test = f"{bridge}.canUnjam(outputs)" if linked_decomposer and not prepaid else "hasEmptyFinalOutput()"
    compact = f"{bridge}.compact(outputs)" if linked_decomposer and prepaid else "compactPrepaidOutputs()"
    files = {f"{java}/{name}.java": (TEMPLATES / f"{name}.java").read_text(encoding="utf-8")
             .replace("@PACKAGE@", package).replace("@NAMESPACE@", namespace)
             .replace("@BUFFERED_DECOMPOSER@", str(mode != DecomposerMode.BATCH).lower())
             .replace("@REGISTER_CLONING@", "        JournalClone.register(bus);" if clone_journal else "")
             .replace("@JOURNAL_LIMIT@", "64" if clone_journal else "1")
             .replace("@PREPAID_DECOMPOSER@", str(prepaid).lower())
             .replace("@REGISTER_MACHINES@", ("List.of(decomposer, Synthesizer394.register(bus, items))" if prepaid else
                                               "SynthesizerFinal.register(bus, items, decomposer)" if final else
                                               "List.of(items.registerSimpleBlockItem(SYNTHESIZER), decomposer)"))
             .replace("@RESEARCH_KEY@", "MaterialRules.validInput(stack) ? id : \"\"" if clone_journal else "id")
             .replace("@FINAL_CAN_UNJAM@", jam_test).replace("@COMPACT_PREPAID@", compact)
             for name in machine_classes(mode) if name not in ("ChemistryRecipes", *SYNTHESIS_394_CLASSES, *SYNTHESIS_FINAL_CLASSES)}
    files[f"{java}/ChemistryRecipes.java"] = _java_recipes(package, namespace, entries, recipes, mode)
    if clone_journal:
        ids = minechem_final_synthesis.chemical_ids(namespace, entries)
        effects = dict((id_, {"coating": coat, "spiking": food, "requires_recreational_effects": optional, "actions": actions})
                       for id_, coat, food, optional, actions in minechem_effects.entries(ids, prepaid=prepaid))
        effects_java = f"{java}/ChemistryEffects.java"
        files[effects_java] = files[effects_java].replace("@EFFECT_ROWS@", minechem_effects.java_rows(ids, prepaid=prepaid))
        files[f"{data}/{namespace}/mcmodup/pharmacology.json"] = document(effects)
        files[f"{assets}/textures/mob_effect/delirium.png"] = minechem_effects.delirium_icon()
        for recipe in ("weapon_coating", "food_spiking"):
            files[f"{data}/{namespace}/recipe/{recipe}.json"] = document({"type": f"{namespace}:{recipe}", "category": "misc"})
        _, nodes = _exact_materials(namespace, entries, prepaid=prepaid, linked=linked_decomposer)
        table = [f"{key}={node.encode()}" for key, node in nodes.items()]
        for rule in recipes:
            if rule.material_key is None:
                continue
            parts = ",".join(f"{item}@{count}" for item, count in rule.outputs)
            table.append(f"!{rule.material_key};{rule.id};{rule.energy};{rule.fluid_input[1] if rule.fluid_input else 0};{parts}")
        files[f"{data}/{namespace}/mcmodup/materials.txt"] = "\n".join(table) + "\n"
        files[f"{data}/{namespace}/recipe/journal_clone.json"] = document({"type": f"{namespace}:journal_clone", "category": "misc"})
    if mode != DecomposerMode.BATCH:
        for name in SYNTHESIS_394_CLASSES if prepaid else SYNTHESIS_FINAL_CLASSES:
            code = (TEMPLATES / f"{name}.java").read_text(encoding="utf-8")
            if name in ("Synthesis394Rules", "SynthesisFinalRules"):
                cases = []
                for row in recipes:
                    if not row.ghost_pattern:
                        continue
                    parts = ", ".join(f'new Part("{part[0]}", {part[1]})' if part else "EMPTY" for part in row.ghost_pattern)
                    extra = f", {str(row.native_output).lower()}" if final else f", {row.declared_slots}"
                    cases.append(f'        new Rule(new Part("{row.id}", {row.outputs[0][1]}), {row.energy}, '
                                 f'{str(row.shaped).lower()}, List.of({parts}){extra})')
                if final:
                    code = code.replace("@RULES@", ",\n".join(cases))
                else:
                    chunks = [cases[i:i + 80] for i in range(0, len(cases), 80)]
                    code = code.replace("@RULES@", ", ".join(f"rules{i}()" for i in range(len(chunks))))
                    code = code.replace("@CHUNKS@", "\n".join(f"    private static List<Rule> rules{i}() {{ return List.of(\n" +
                                        ",\n".join(chunk) + "\n    ); }" for i, chunk in enumerate(chunks)))
            files[f"{java}/{name}.java"] = code.replace("@PACKAGE@", package).replace("@NAMESPACE@", namespace)
    locales = {f"gui.{namespace}.{key}": label for key, label in {
        "process": "Process", "auto": "Auto", "manual": "Manual",
        "clear_ghosts": "Clear", "ghost_help": "Template slots: copy the held count with left-click, adjust with right-click.",
        "synthesis_cost": "Batch: %s FE", "synthesis_power": "Stored: %s FE",
        "auto_help": "Toggle repeated processing using the current recipe, input and FE",
        "decompose": "Insert one material to split", "charge": "Optional fuel: Redstone Dust (+500 FE when needed)",
        "water_tank": "%s %s/%s mB", "empty_tank": "Empty tank",
        "tank_water": "Water", "tank_lava": "Lava", "tank_empty": "Fluid:",
        "requirement": "%s × %s", "recipe_help": "Recipe ingredients (hold Shift to skip 10 recipes)",
        "state_0": "Ready · %s FE", "state_1": "Working · %s FE", "state_2": "Need input · %s FE",
        "state_3": "Need power · %s FE", "state_4": "Output full · %s FE", "state_5": "Done · %s FE",
        "inspect_sample": "Insert an item to inspect", "inspect_book": "Insert a journal to record the recipe",
        "inspect_synthesis": "Synthesis", "inspect_decomposition": "Decomposition",
        "inspect_inputs": "Ingredients · %s FE", "inspect_outputs": "Products · %s FE",
        "inspect_example": "Example yield · %s FE",
        "no_recipe": "No supported recipe in this mode",
        "journal_owner": "Last used by %s", "journal_count": "Discovered recipes: %s", "journal_active": "Active: %s",
        "journal_empty": "Discover recipes in a Microscope", "journal_choose": "Choose a discovered recipe",
        "journal_previous": "Select previous discovery", "journal_next": "Select next discovery",
        "load_journal": "Insert a journal to load its active synthesis recipe",
        **({"final_template_help": "Copy chemicals into the ghost grid; load real chemicals below. Left-click adds a stack, right-click adds one, empty hand clears.",
            "final_ready": "Ready", "final_unready": "Not ready", "final_unsupported": "Legacy result not yet available",
            "final_charge": "%s / %s FE", "final_take_help": "Click pays per item; shift-click makes full batches.",
            "final_template_count": "Template: %s"} if final else {}),
    }.items()}
    if clone_journal:
        locales.update({f"item.{namespace}.polytool": "Polytool", f"effect.{namespace}.delirium": "Delirium",
                        f"item.{namespace}.treatment.coated": "Coated with %s", f"item.{namespace}.treatment.spiked": "Mixed with %s"})
        files[f"{assets}/models/item/polytool.json"] = document({
            "parent": "minecraft:item/handheld", "textures": {"layer0": f"{namespace}:item/polytool"},
        })
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
    if final:
        files[f"{assets}/models/block/synthesizer.json"] = document({
            "parent": "minecraft:block/cube_all", "textures": {"all": f"{namespace}:block/synthesizer"},
        })
        locales[f"item.{namespace}.atomic_manipulator"] = "Atomic Manipulator"
        files[f"{assets}/models/item/atomic_manipulator.json"] = document({
            "parent": "minecraft:item/generated", "textures": {"layer0": f"{namespace}:item/atomic_manipulator"},
        })
        files[f"{data}/{namespace}/recipe/atomic_manipulator.json"] = document({
            "type": "minecraft:crafting_shaped", "category": "misc", "pattern": ["PPP", "PIP", "PPP"],
            "key": {"P": {"item": "minecraft:piston"}, "I": {"item": "minecraft:iron_block"}},
            "result": {"id": f"{namespace}:atomic_manipulator", "count": 1},
        })
        path = f"{data}/{namespace}/recipe/synthesizer.json"
        recipe = json.loads(files[path])
        recipe["key"]["P"] = {"item": f"{namespace}:atomic_manipulator"}
        files[path] = document(recipe)
    files[f"{data}/minecraft/tags/block/mineable/pickaxe.json"] = document(
        {"replace": False, "values": [f"{namespace}:{block}" for block in blocks]})
    return files, locales
