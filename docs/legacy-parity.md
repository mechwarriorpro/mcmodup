# Full-parity acceptance for the one-JAR build

The target is **one installable JAR for Minecraft 1.21.1 / NeoForge 21.1.267**, containing both exact Minechem releases and the requested Voltz and GeoStrata systems. Booting the game, remapping class names, or passing a subset of GameTests is not full parity. The current build still requires `--allow-preview-ports` and reports `parity_verified: false`.

Use the pinned binaries in `mcmodup/legacy_testbed.py` as the reference, including CalclaviaCore from Voltz Server 3.0.0c. Later releases, wiki descriptions, and a class-name inventory are not substitutes for running the exact versions. The two Minechems must also work together without accidentally mixing recipes, item metadata, saved inventories, research, or power behavior.

## Open acceptance areas

| System | Evidence so far | Still required before calling it parity |
| --- | --- | --- |
| Minechem 5.0.5 FINAL (1.6.4) | Original synthesis order, ghost editing, paid extraction and sided gates; iron queue, water and journal copy traces; projector and item-fueled reactors. | Polytool output and original client/UI integrations, complete material and decomposition recipes including random and recursive rules, fluids and effects, radiation and shielded storage, world generation, remaining items and progression, original configuration and integrations. |
| Minechem 5.0.5.394 (1.7.10) | Original decomposition and synthesis subsets, water, journal automation and paid extraction; projector and RF reactor traces replayed with native FE. | Remaining recipes and ambiguous patterns, chemistry and radiation, shielded storage, native equivalents for original world and item effects, configuration and integrations. |
| ICBM 1.4.1.344 launchers, missiles and Sentries | Three launcher tiers, two payloads, AA, gun and laser behavior, platform FE and inventory. | Original missile assembly and component/payload catalog, launcher and targeting UI, flight/impact behavior, all turret roles (including railgun), ammo and energy/sided behavior, recipes, persistence, and client models and effects. |
| Atomic Science particle accelerator | Powered ring test, visible beam, collection and stored antimatter output. | Original accelerator inventory/UI and ring acceptance, beam motion, collisions/invalid paths, dark matter, full antimatter handling and its Voltz interactions. |
| GeoStrata v23b crystals | Shards, lamps, auras and adapted cave generation; block models load. | Original generation and dimension/effect behavior, all crystal crafts and the brewing, plant and pendant chains, clusters and guardian interactions, and the original render behavior. |
| CalclaviaCore Creative Builder | Two rotatable accelerator rings. | Original schematic picker, size, rotation and placement semantics for all applicable schematics, including the Atomic Science fission, breeding and fusion structures and the blocks they use. |

The native build uses deliberate substitutions at some boundaries. For example, .394 has a paired-only recipe variant where the two legacy recipes have identical inputs, reactors use FE where the original used RF, and projectors are not allowed to overwrite an occupied core site. Record such differences even if the alternative is more convenient. They aren't evidence that the original behavior was reproduced.

## Minechem feature in progress: FINAL synthesizer

The [exact Forge runtime replay](https://github.com/mechwarriorpro/mcmodup/actions/runs/44492046641/job/135449864766) re-observed **301 ordered registrations**: 278 successfully paid canonical patterns, 22 patterns that selected an earlier registration, and one recipe beyond the machine's 1,022,220-unit buffer. The machine accepted up to 200 units per receive. The ghost grid, not storage, selected the recipe. Shaped recipes checked the declared positions and accepted excess amounts; shapeless recipes required exact stack counts. For example, the seven-stone preview cost 500 per withdrawal unit by regular click, but 500 per seven-stone batch by shift-click. The iron recipe paid at exactly 10,000, drawing 16 iron tubes across storage slots. The probe also checked journal selection, removal and reload, then toggled the original automation gate and checked all six faces.

The native implementation keeps these recipes in the original order and tests the canonical winner, exact charge and paid output. It uses the original GUI texture and coordinates, but NeoForge FE and native menu controls replace UE and the original tabs. It refreshes edited ghosts immediately, prevents inserts into the virtual result, and stops bulk crafting before paying for a batch that will not fit. The Polytool row still blocks later matching rules but does not produce an item until that functionality is ported. This does not establish arbitrary add-on recipes or every configuration-dependent outcome. See the [playable workflow](legacy-testbed.md#minechem-final-synthesizer); older timed-preview synthesizer data is not migrated.

## Evidence required for a release claim

1. **Original runtime:** Record what the exact binary registers and what players can do with it on Forge: recipes and progression, machine reactions and gates, faces, save/reload, failure states and client interactions. Check configuration-dependent paths as well as defaults.
2. **Native comparison:** Replay those cases on NeoForge and assert the observable outputs and side effects. A synthetic happy path alone is not enough. A behavior that was intentionally changed must be identified, not counted as an exact match.
3. **Single-JAR integration:** Run the cases against the packaged JAR, not just the projects used to build it. Load both Minechems, exercise cross-system interactions, and verify clean dedicated-server and graphical-client runs, including usable screens, world reloads and resource reloads. Tie the result to the tested artifact's SHA-256.
4. **Compatibility:** Original saves, binary add-ons, and arbitrary third-party integration packs do not work with the current native build. They require their own migration or compatibility tests; a successful fresh-world check proves none of them.

See the [install and gameplay guide](legacy-testbed.md) for the bounded preview and links to the actual verification runs. Keep derivatives private unless redistribution rights for the exact inputs have been established.
