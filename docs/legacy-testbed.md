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

- **Minechem:** Place `minechem_chemistry:decomposer` and `minechem_chemistry_394:decomposer` apart and put an iron ingot in each. Place a Creative FE Supply (`atomic_thermometer:creative_fe_supply`) next to each one. Compare the separately namespaced iron tubes and the input/energy behavior. The .394 Synthesizer has a template grid and charges for the real output when it is taken, not when its preview appears. The FINAL Synthesizer uses the adapted batch menu. Both versions have a microscope and an independent journal.
- **ICBM:** Place a Launcher Base, a matching Launcher Screen beside it, and a matching Launcher Frame on another side of the base. Keep the block directly above the base open. Mark a block at least ten blocks away with a Target Designator, use the designator on the screen, and insert a Training Missile into the base. Charge the screen with the Creative FE Supply (or right-click the screen with a lightning rod in creative). Apply a redstone pulse to the screen, or sneak-use the screen with an empty hand. Use a nearby target in loaded chunks. Training missiles do not destroy blocks. A nearby powered Anti-Air Turret loaded with iron nuggets can intercept a missile; the Gun and Laser Turrets instead look for hostile mobs. Gun Turrets use iron nuggets and FE, while lasers need FE only.
- **Atomic Science and AutoBuilder:** Place a Creative Builder (`atomic_thermometer:creative_builder`) in a clear loaded area. Use it with an empty hand in creative to build an accelerator ring two blocks above it. Sneak-use builds the larger ring. Rotation follows the builder's facing. Leave at least 15×15 blocks clear for the larger blueprint and keep all three ring layers unobstructed. Insert a stack of cobblestone and one or more Empty Cells into the Particle Accelerator by using each item on it. Supply redstone power from the side. A Creative FE Supply can power it normally; for a shorter creative test, use a lightning rod on the accelerator to enable the fast creative beam feed. Let the beam accumulate 125 mg, then sneak-use the accelerator with an empty hand to collect the cell. An empty-hand use without sneaking shows status, stored FE and collected mass. The fast mode must be enabled again after reload.
- **GeoStrata:** Search for Cave Crystals in the GeoStrata creative tab or explore newly generated caves. Cave Crystals drop matching shards. Four shards and three stone slabs make a Crystal Lamp. A matching lamp, redstone block, glowstone, two obsidian and a gold block make the corresponding Potion Crystal. For example, a light-blue Potion Crystal applies Speed nearby, while a white Cave Crystal clears effects. The decorative lamps don't apply auras.

The Creative FE Supply has no survival recipe and is for testing. Ordinary FE suppliers from other NeoForge mods can feed supported machines; legacy RF/UE integrations are not provided.

## Verified build

[Run 44484066723](https://github.com/mechwarriorpro/mcmodup/actions/runs/44484066723/job/135423057286) passed **33 component tests and 16 staged GameTests** on NeoForge 21.1.267. The produced JAR booted on a clean dedicated server without other mods. A separate staged client check entered a fresh world, checked the connected-wall rendering and selected models from all five components, and reloaded resources. These are gameplay and loading checks, not original-client visual parity.

The SHA-256 of the JAR tested in that run was `932d1234f66d60a421dd3e53c0bf5ef306dd8cba2b65d5b8320c639cb7a40da5`. This identifies that runner's artifact; use the report next to your local output for its own hash.

## What is still missing

| System | Current boundary |
|---|---|
| Minechem FINAL and .394 | Catalog chemistry, a subset of the material and synthesis rules, research and journaling, separately modeled water intake and machine behavior. **Not all Minechem features.** Other fluid chemistry, radiation, fusion and fission multiblocks, blueprint projector, and the full recipe and integration chains are not implemented. |
| ICBM | Three tier capacities and range limits, two missile types, and AA/gun/laser turrets. No modular missile assembler, full payload catalog, mounted railgun, legacy networking/GUI, or original flight physics. Recipes, models, loaded-area flight and Sentry combat are adapted. |
| Atomic Science | Planar electromagnet loop, redstone/FE gates, visible beam and saved antimatter output; also the earlier heat, thermometer, funnel and turbine preview. No particle collisions, dark matter, legacy antimatter weapons, or reactors. |
| GeoStrata | Sixteen colors of cave crystals, shards, lamps and potion crystals, plus the rock preview. Recreated models and cave placement, partial Nether effects; no brewery, blooms, pendants, crystal clusters or guardian. |
| Old AutoBuilder | Based on the scanned CalclaviaCore Creative Builder; two rotatable accelerator-ring blueprints. No original blueprint picker, arbitrary sizes, other Voltz schematics or automatic replacement of an occupied site. |

The class inventory under `META-INF/mcmodup/` records the originals used for reference; it does not mean every scanned class was ported. `parity_verified` remains false. See [the one-JAR verification runs](https://github.com/mechwarriorpro/mcmodup/actions/workflows/legacy-testbed.yml) for the reports that refer to a tested production JAR by its SHA-256.
