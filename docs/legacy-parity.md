# Full-parity acceptance for the one-JAR build

The target is **one installable JAR for Minecraft 1.21.1 / NeoForge 21.1.267**, containing both exact Minechem releases and the requested Voltz and GeoStrata systems. Booting the game, remapping class names, or passing a subset of GameTests is not full parity. The current build still requires `--allow-preview-ports` and reports `parity_verified: false`.

Use the pinned binaries in `mcmodup/legacy_testbed.py` as the reference, including CalclaviaCore from Voltz Server 3.0.0c. Later releases, wiki descriptions, and a class-name inventory are not substitutes for running the exact versions. The two Minechems must also work together without accidentally mixing recipes, item metadata, saved inventories, research, or power behavior.

## Open acceptance areas

| System | Evidence so far | Still required before calling it parity |
| --- | --- | --- |
| Minechem 5.0.5 FINAL (1.6.4) | Original iron queue, water thresholds and journal copy traces; projector grids and item-fueled reactor output replayed in the one-JAR build. | Complete material/chemical recipes including random and recursive rules, the original synthesizer workflow, fluids and effects, radiation and shielded storage, world generation, remaining items and progression, original configuration and integrations. |
| Minechem 5.0.5.394 (1.7.10) | Original decomposition and synthesis subsets, water, journal automation and paid extraction; projector and RF reactor traces replayed with native FE. | Remaining recipes and ambiguous patterns, chemistry and radiation, shielded storage, native equivalents for original world and item effects, configuration and integrations. |
| ICBM 1.4.1.344 launchers, missiles and Sentries | Three launcher tiers, two payloads, AA, gun and laser behavior, platform FE and inventory. | Original missile assembly and component/payload catalog, launcher and targeting UI, flight/impact behavior, all turret roles (including railgun), ammo and energy/sided behavior, recipes, persistence, and client models and effects. |
| Atomic Science particle accelerator | Powered ring test, visible beam, collection and stored antimatter output. | Original accelerator inventory/UI and ring acceptance, beam motion, collisions/invalid paths, dark matter, full antimatter handling and its Voltz interactions. |
| GeoStrata v23b crystals | Shards, lamps, auras and adapted cave generation; block models load. | Original generation and dimension/effect behavior, all crystal crafts and the brewing, plant and pendant chains, clusters and guardian interactions, and the original render behavior. |
| CalclaviaCore Creative Builder | Two rotatable accelerator rings. | Original schematic picker, size, rotation and placement semantics for all applicable schematics, including the Atomic Science fission, breeding and fusion structures and the blocks they use. |

The native build uses deliberate substitutions at some boundaries. For example, .394 has a paired-only recipe variant where the two legacy recipes have identical inputs, reactors use FE where the original used RF, and projectors are not allowed to overwrite an occupied core site. Record such differences even if the alternative is more convenient. They aren't evidence that the original behavior was reproduced.

## Evidence required for a release claim

1. **Original runtime:** Record what the exact binary registers and what players can do with it on Forge: recipes and progression, machine reactions and gates, faces, save/reload, failure states and client interactions. Check configuration-dependent paths as well as defaults.
2. **Native comparison:** Replay those cases on NeoForge and assert the observable outputs and side effects. A synthetic happy path alone is not enough. A behavior that was intentionally changed must be identified, not counted as an exact match.
3. **Single-JAR integration:** Run the cases against the packaged JAR, not just the projects used to build it. Load both Minechems, exercise cross-system interactions, and verify clean dedicated-server and graphical-client runs, including usable screens, world reloads and resource reloads. Tie the result to the tested artifact's SHA-256.
4. **Compatibility:** Original saves, binary add-ons, and arbitrary third-party integration packs do not work with the current native build. They require their own migration or compatibility tests; a successful fresh-world check proves none of them.

See the [install and gameplay guide](legacy-testbed.md) for the bounded preview and links to the actual verification runs. Keep derivatives private unless redistribution rights for the exact inputs have been established.
