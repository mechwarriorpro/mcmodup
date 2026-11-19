# One-JAR legacy gameplay testbed

This is an **incomplete native preview for Minecraft 1.21.1 and NeoForge 21.1.267**, not a replacement for the original mods. It combines the two Minechem previews, the ICBM and Atomic Science previews, and the GeoStrata preview into one ordinary mod JAR. The two Minechem releases keep separate items, blocks, recipes and saved inventories. No Connector, old Forge, Universal Electricity, DragonAPI, or additional mod JAR is needed to try these scenarios.

## Build and install

From the repository root, with **Python 3.11+ and a full JDK 21** on `PATH` or configured in `JAVA_HOME`:

```sh
python -m mcmodup build-legacy-testbed --fetch-originals --allow-preview-ports -o ports/legacy-testbed
```

Use a new output directory. The command downloads the four checksum-pinned originals into temporary storage, builds and runs the component GameTests, and writes:

- `mcmodup-legacy-testbed-1.21.1.jar` — the only file to put in the `mods` folder of a **fresh NeoForge 21.1.267 instance**.
- `testbed-report.json` — component coverage, limitations, original class inventory, and the output SHA-256. Not a mod file.

If you already have the original files, you can supply them instead of downloading them:

```sh
python -m mcmodup build-legacy-testbed \
  'Minechem-1.6.4-5.0.5.FINAL.jar' \
  'Minechem-1.7.10-5.0.5.394.jar' \
  'Voltz_Server_v3.0.0c.zip' \
  'GeoStrata 1.6 v23b.zip' \
  --allow-preview-ports -o ports/legacy-testbed
```

Use the full Voltz server ZIP, not a directory containing extracted JARs. A first Gradle build still needs network access for the wrapper, NeoForge and Minecraft dependencies. The original files are not modified. The CI workflow builds the same private-use JAR from the pinned inputs, boots it alone in a dedicated server, and runs staged tests against that JAR. No private-use GeoStrata assets or packaged derivative archive are published by CI.

Do not install the old mods or the separate native previews alongside this JAR. Use a throwaway world, not a legacy save or an existing modpack. Review the notices inside the JAR and keep the output private unless you have permission to redistribute the original assets and adaptations.

## Try the gameplay in a fresh creative world

- **Minechem:** Place `minechem_chemistry:decomposer` and `minechem_chemistry_394:decomposer` apart and put an iron ingot in each. Place a Creative FE Supply (`atomic_thermometer:creative_fe_supply`) next to each one. Compare the separately namespaced iron tubes and the input/energy behavior. Both Synthesizers select templates in a ghost grid and charge only when output is taken. The [FINAL workflow below](#minechem-final-synthesizer) and [.394 workflow](usage-examples.md#minechem-505394-machine-preview) differ in slot layout, charge, and batch behavior. Each release has its own microscope and journal.
- **ICBM:** Place a Launcher Base, a matching Launcher Screen beside it, and a matching Launcher Frame on another side of the base. Keep the block directly above the base open. Mark a block at least ten blocks away with a Target Designator, use the designator on the screen, and insert a Training Missile into the base. Charge the screen with the Creative FE Supply (or right-click the screen with a lightning rod in creative). Apply a redstone pulse to the screen, or sneak-use the screen with an empty hand. Use a nearby target in loaded chunks. Training missiles do not destroy blocks. A nearby powered Anti-Air Turret loaded with iron nuggets can intercept a missile; the Gun and Laser Turrets instead look for hostile mobs. Gun Turrets use iron nuggets and FE, while lasers need FE only.
- **Atomic Science and AutoBuilder:** Place a Creative Builder (`atomic_thermometer:creative_builder`) in a clear loaded area. Use it with an empty hand in creative to build an accelerator ring two blocks above it. Sneak-use builds the larger ring. Rotation follows the builder's facing. Leave at least 15×15 blocks clear for the larger blueprint and keep all three ring layers unobstructed. Insert a stack of cobblestone and one or more Empty Cells into the Particle Accelerator by using each item on it. Supply redstone power from the side. A Creative FE Supply can power it normally; for a shorter creative test, use a lightning rod on the accelerator to enable the fast creative beam feed. Let the beam accumulate 125 mg, then sneak-use the accelerator with an empty hand to collect the cell. An empty-hand use without sneaking shows status, stored FE and collected mass. The fast mode must be enabled again after reload.
- **GeoStrata:** Search for Cave Crystals in the GeoStrata creative tab or explore newly generated caves. Cave Crystals drop matching shards. Four shards and three stone slabs make a Crystal Lamp. A matching lamp, redstone block, glowstone, two obsidian and a gold block make the corresponding Potion Crystal. For example, a light-blue Potion Crystal applies Speed nearby, while a white Cave Crystal clears effects. The decorative lamps don't apply auras.

The Creative FE Supply has no survival recipe and is for testing. Ordinary FE suppliers from other NeoForge mods can feed supported machines; legacy RF/UE integrations are not provided.

## Minechem FINAL synthesizer

1. Make an **Atomic Manipulator** from eight pistons around an iron block. For the Synthesizer, use `IRI / IAI / IDI`, with iron ingots (`I`), redstone dust (`R`), the manipulator (`A`), and a diamond (`D`). This recipe belongs to `minechem_chemistry`, not .394.
2. Copy chemicals from the cursor into the upper 3×3 ghost grid: left-click adds the held count, right-click adds one, and an empty cursor clears the cell. For iron, copy a stack of exactly **16 iron tubes** into any one ghost slot. Or inspect an iron ingot with a FINAL Chemist's Journal in the microscope, select the discovery in the held journal, then put the book into the Synthesizer's left slot. Taking the journal back clears the template, not the stored ingredients.
3. Add the **real** tubes in the nine storage slots below the grid. Connect an FE supply; FINAL holds up to **1,022,220 FE** and receives up to **200 FE per transfer**. With at least **10,000 FE** and 16 real tubes (split among storage slots if needed), take the iron ingot. There's no timed job, redstone-dust fuel slot, or cost for previewing the item.
4. For multi-item recipes, **shift-click** to make complete batches. For example, a stone batch is seven stone for 500 FE, one silicon tube, and two oxygen tubes (silicon in the top-left ghost slot, oxygen directly below). Like the original, a regular click pays those inputs and charge per stone withdrawn. Shift-click won't spend resources on results that can't fit.

Sided item insertion can reach storage while idle; once ready, non-top faces show the result and the top still shows storage. Withdrawal automation defaults to off. To allow it, set `allowAutomation = true` in the configuration file `minechem_chemistry-synthesis.toml` for the world, then reload the world. This option is independent of .394. The journal and ghosts are never automation slots. Keep a backup and empty any earlier preview Synthesizers before upgrading; the timed-preview block entity data is not migrated.

## Minechem blueprint projectors and reactors

These are separate builds of the same in-game machines, not cross-compatible inventories. Use a projector, blueprint and reactor blocks from the **same release**. In survival, concave and convex lenses make the projector and microscope lenses. Paper plus a diamond makes a Fission Blueprint; paper plus a diamond block makes a Fusion Blueprint. If both releases are loaded, use a blank map instead of paper and glass panes instead of glass blocks for the **.394** blueprint and basic lens recipes. The recipe inputs are otherwise identical in the two originals, and the crafting grid cannot select between identical recipes by mod ID.

1. Place the Blueprint Projector while looking toward a clear site, then insert a blueprint. The first unfinished vertical slice appears as replaceable ghost blocks. Fill the ghosts with matching Reactor Casings and Coils one slice at a time. The **5×5×5 fission** shell uses 98 coils; the **13×5×13 fusion** structure uses 181 casings and 241 coils. Leave required air cells and the core location empty. Retrieving the blueprint clears its ghosts, not placed blocks. When the structure is ready, the projector forms the core. Use the core or a linked wall to open it.
2. **FINAL:** For fission, put fluorine in the input and a uranium tube in the fuel slot. On an eligible tick it consumes one of each and yields two beryllium tubes. For fusion, place hydrogen in both input slots and a Nether Star in the fuel slot. The first successful batch yields helium and changes the fuel into a Fusion Star; repeat use adds wear. Neither core takes FE in this release.
3. **.394:** Supply FE to a core or linked reactor wall. The buffer holds 100,000 FE at up to 100 FE per receive call. Fluorine fission costs 900 FE, payable at exactly 900. Hydrogen plus hydrogen fusion costs 200 FE but starts only **above** 200. With an open output, lithium yields two hydrogen tubes in .394; FINAL consumes the lithium and uranium without producing a tube.

[Exact-runtime comparison](https://github.com/mechwarriorpro/mcmodup/actions/runs/44486851624): the Forge probes matched both full blueprint grids, measured the fission ghost slices (25 then 16), removed the blueprint, built a core, and exercised each release’s distinct reaction and cost rules. The native build uses these shapes and rules but deliberately doesn't overwrite an occupied core location, load remote chunks, or overflow a native item stack. It imports original menu textures and sound, not the original animated projector renderer or menu tabs.

## Verified build

[Run 44492694048](https://github.com/mechwarriorpro/mcmodup/actions/runs/44492694048/job/135452042383) passed **43 component GameTests and 26 tests against the assembled JAR** on NeoForge 21.1.267. The packed FINAL tests replayed 277 paid canonical patterns, 22 earlier winners, and two unavailable outcomes. The same JAR booted and shut down on a clean dedicated server without other mods. In a fresh world, the graphical client checked models from all five components, reloaded resources, synced an actual .394 projector blueprint, and used FINAL's Synthesizer to take a paid output and retrieve its journal. That screen correctly synced a 144-tube ghost and the full stored charge without treating the ghost as a real stack.

That runner's JAR had SHA-256 `43eb53e462ea0fb737fa8d1b625b870a6b6b9d062d7e50bbd833724e828e3660`. Use the report next to your local output for its own hash. This is not an original-client visual comparison or evidence of full parity.

## What is still missing

| System | Current boundary |
|---|---|
| Minechem FINAL and .394 | Catalog chemistry, release-specific synthesis, journals, water intake, the two projector blueprints and release-specific fusion/fission. **Not all Minechem features.** Other fluid chemistry, radiation, the remaining machines, and the full recipe, item-effect, worldgen and integration chains are incomplete. |
| ICBM | Three tier capacities and range limits, two missile types, and AA/gun/laser turrets. No modular missile assembler, full payload catalog, mounted railgun, legacy networking/GUI, or original flight physics. Recipes, models, loaded-area flight and Sentry combat are adapted. |
| Atomic Science | Planar electromagnet loop, redstone/FE gates, visible beam and saved antimatter output; also the earlier heat, thermometer, funnel and turbine preview. No particle collisions, dark matter, legacy antimatter weapons, or reactors. |
| GeoStrata | Sixteen colors of cave crystals, shards, lamps and potion crystals, plus the rock preview. Recreated models and cave placement, partial Nether effects; no brewery, blooms, pendants, crystal clusters or guardian. |
| Old AutoBuilder | Based on the scanned CalclaviaCore Creative Builder; two rotatable accelerator-ring blueprints. No original blueprint picker, arbitrary sizes, other Voltz schematics or automatic replacement of an occupied site. |

The class inventory under `META-INF/mcmodup/` records the originals used for reference; it does not mean every scanned class was ported. `parity_verified` remains false. See the [full-parity acceptance checklist](legacy-parity.md) and [the one-JAR verification runs](https://github.com/mechwarriorpro/mcmodup/actions/workflows/legacy-testbed.yml) for the reports that refer to a tested production JAR by its SHA-256.
