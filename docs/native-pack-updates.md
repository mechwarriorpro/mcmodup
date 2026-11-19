# Move selected mods into a new NeoForge pack

Use `pack-ui` for a checklist or `update-pack` for a scriptable plan. The updater looks in your installed packs for **native Minecraft 1.21.1 / NeoForge** builds. It copies compatible local JARs or [builds reviewed native source ports](source-port-builds.md); it does not translate arbitrary old Forge bytecode or use Legacy Connector at runtime.

## Inputs

```text
LegacyPack/
  mods/                    Original .jar / .zip files, including nested and versioned folders
  coremods/                Also scanned recursively; a pack can have only coremods/
  bin/minecraft.jar        Original game API (when installed in this launcher layout)
  bin/modpack.jar          Original Forge/FML API in older launcher packs
  lib/ or libraries/       Local dependency evidence and/or inactive native candidates
  mcmodup-updates.json     Optional reviewed legacy-to-native ID map
NeoForgePack/
  mods/                    Already installed native candidates; may be empty or absent
  lib/ or libraries/       Other local native candidates, not yet active
  libraries/net/neoforged/neoforge/21.1.x/   Stock server installer layout, or:
  bin/                     Installed Minecraft 1.21.1 and NeoForge 21.1.x universal JARs
```

Use actual installed folders, not launcher export manifests. `scan-pack`, `plan-port`, and `decompile-mod` can use the local Minecraft and Forge JARs as signature inputs when the pack establishes the exact supported legacy release and no explicit `--legacy-api` was supplied. Minecraft `version.json`, supported vanilla client fingerprints, and Forge's `fmlversion.properties` are evidence; a filename alone is not. Bare libraries outside the loader folders can be reported as local provider evidence by the updater, but are not assumed to be enabled or installed as mods. The updater never copies the source runtime or legacy Forge/coremod code into `mods/`.

A NeoForge installer JAR alone is not an installed target. Source and target may be the same folder only if it already has an identifiable 1.21.1 runtime. Use `--target` for separate installations. Supply renamed mod IDs in `LegacyPack/mcmodup-updates.json` (e.g. `{"appliedenergistics2": "ae2"}`), or use `--id-map /path/to/ids.json` to leave both inputs untouched. These mappings are your review decisions, not evidence of feature equivalence.

## Create an instance or stage a mods-folder swap

```sh
# Inspect the source selection and its version-resolved add-ons and dependencies
python3 -m mcmodup update-pack LegacyPack --target NeoForgePack \
  --mod mods/1.7.10/OldMod.jar --json

# Write a fresh instance with the selected mods, their add-ons/dependencies, and target runtime files
python3 -m mcmodup update-pack LegacyPack --target NeoForgePack \
  --mod mods/1.7.10/OldMod.jar --mod coremods/Other.jar \
  --include-native cb_multipart --output-pack .mcmodup/NewPack

# Alternatively, keep the target's other active mods and stage a replacement mods/ folder only
python3 -m mcmodup update-pack LegacyPack --target NeoForgePack \
  --mod mods/1.7.10/OldMod.jar -o .mcmodup/staged-mods

# Same new-instance workflow with a checklist
python3 -m mcmodup pack-ui LegacyPack --target NeoForgePack --output-pack .mcmodup/NewPack
```

Both output modes require a new directory outside the input packs. Neither modifies either input. Open the local workbench URL (default `http://127.0.0.1:8765/`) to review the plan before writing; omit the destination for read-only use. Omit `--mod` in the CLI to list available selections.

- **`--output-pack`** does not inherit unrelated target mods, disabled files, or configs. It copies a stock NeoForge server install's relative launch-argument files and the local libraries referenced by those files and NeoForge’s runtime locators. Start from the new folder with Java 21 using the command in `INSTALL.txt`. For a `bin/` target, it writes a new **game directory** with the identified modern JARs; point an existing 1.21.1 NeoForge launcher profile at it. It is not a standalone launcher or an installed server in this layout.
- To carry an installed native mod into the new pack, pass **`--include-native MOD_ID`** (repeatable, CLI only). In the example, install the native CB Multipart JAR in `NeoForgePack/mods/` first, or omit the flag if you don't want it. The updater requires exactly one compatible installed JAR for that ID and copies the entire archive unchanged, including its other mod IDs. It resolves required dependencies such as CodeChickenLib normally and reports the explicit copy as `include-native`, not `convert`. You can also use this with no `--mod` selections for a native-only pack.
- **`-o` / `--output`** keeps the existing mods-swap behavior. It stages the target's complete `mods/` folder and does not copy the runtime. A leftover non-native target mod is a blocker unless the plan replaces all of its IDs. Close the game, back up `mods/`, then **replace** that folder; do not merge it with stale JARs.

`update-report.json` records file origins and SHA-256s, legacy declarations and class owners, native constraints and provider choices, and any runtime files copied. No saves, credentials, configs or license/EULA acceptance are copied. Start in a fresh world. A ready or staged plan is not a boot check, proof of saved-world migration, or proof of gameplay or resource parity.

## Preview automatic recipe translation

The updater can recognize a bounded subset of original Forge **1.5.2, 1.6.4, and 1.7.10** archives on an empty or existing **1.21.1 / NeoForge 21.1.267** installation. To try it, stage **one** eligible archive in a new pack:

```sh
python3 -m mcmodup update-pack LegacyPack --target NeoForgePack \
  --mod mods/Original.jar --allow-recipe-preview --output-pack .mcmodup/RecipePreview
```

The same flag works with `pack-ui` and `--output-pack`. A recipe preview is not selected by “Select available,” cannot be combined with `--include-native`, cannot replace a target's installed mods folder, and cannot satisfy a native dependency or substitute for a full mod update. If a compatible local native build is available, the updater still prefers that build. For an original-only test, use a clean target without any replacement JARs in its scanned folders.

The translator reads bytecode without running it. It needs exact Minecraft release evidence and checks every method in a small, single-class FML archive. It supports literal shaped and shapeless recipes using a limited set of vanilla items, plus some numeric-ID furnace registrations. It maps known wool metadata to the corresponding modern colored items. Unknown calls, nontrivial initialization, custom assets, real dependency or network handlers, and unsupported item identities block translation. The result is a deterministic `lowcodefml` JAR, with no legacy classes. `META-INF/mcmodup/recipe-conversion.json` records the original SHA-256, recipe counts, and limitations. Generating it requires Python, not a Java compiler; launching the target requires Java 21.

**A recipe preview does not meet full parity.** Legacy client/server requirements and startup log messages are not reproduced, and positive furnace XP uses modern accounting. The preview is not validated for add-ons, interactions with other mods, or old saves. Staging for your input verifies metadata and hashes, not gameplay.

The [original recipe workflow](../.github/workflows/recipe-conversion.yml) downloads pinned public originals and tests them on historical Forge. Each generated JAR also boots on stock NeoForge with no replacement mod or probe, then runs the recipe checks alongside a test-only probe. The [recorded run](https://github.com/mechwarriorpro/mcmodup/actions/runs/44352910963) measured:

| Original | Observed on Forge and NeoForge |
| --- | --- |
| 1.5.2 Rotten Flesh to Leather 1.2.2 | One rotten flesh smelts to one leather in 200 ticks; a normal pickup awards 1 XP. After two non-player extractions and one pickup, the original awards 1 XP and the preview awards 3 XP. |
| 1.6.4 Zombie Leather 1.0.0 | One rotten flesh smelts to one leather in 200 ticks; a normal pickup awards 0 XP on both. |
| 1.7.10 Craftable Saddles 1.2 | All five shaped recipes pass 241 cases on each runtime, including tested offsets, mirroring, ingredient variants, and invalid inputs. |

These hand-picked previews do not count toward full parity. The [random original-only benchmark](https://github.com/mechwarriorpro/mcmodup/actions/runs/44352029826) is separate: 0 of 3 originals produced a JAR in the normal updater. Two official native controls staged and booted; neither is an automatic conversion.

## Different releases of selected originals and their dependencies

Keep legacy originals grouped by Minecraft release (for example, `mods/1.6.4/` and `mods/1.7.10/`). The updater resolves release evidence per archive; this is a workspace layout, not an old Forge installation that runs both releases. If distinct selected originals share an ID, the planner reports `legacy_selection_conflicts` unless distinct SHA-bound recipes are supplied for them. It will not count one modern JAR as preserving both originals. A global ID alias alone cannot separate class, registry, resource and save identities. The [paired Minechem recipes](source-port-builds.md#both-exact-minechem-releases-linked-previews) demonstrate isolation for a fresh world, not a migration of either old registry.

For legacy IDs with characters not allowed by NeoForge, such as `ICBM|Explosion`, the planner reports the invalid native ID. A reviewed map can name the corresponding IDs in an actual native port; it does not update the underlying module code.

The planner resolves required old declarations per consumer. For example, an add-on demanding an exact Forge Multipart release is not automatically added when you select a *different* Multipart release. The report records each compatible local original provider and its digest, or records why it could not identify one. Old ranges are **not** applied to modern JARs. The native add-ons' declarations define which modern versions can run together.

- If one local native provider satisfies all required consumers (and present optional constraints), stage a single provider. `shared_native_provider` shows the selected JAR and every constraint. An already installed compatible version is preferred. If several otherwise equivalent solutions work, use `--provider MOD_ID=SHA256` to pin a reviewed local artifact. This cannot force an incompatible release. The resolver checks transitive dependencies too.
- If modern ranges are disjoint, staging stops; do not install two top-level JARs with the same mod ID or duplicate classes. Forge Multipart/Forge Microblocks can be mapped to a **single** modern CB Multipart JAR if the native consumers were ported to that API. Shared parts, registries, packets, mixins, and transformers need a native port or adapter. Changing a filename or shading two copies of an old coremod does not isolate them.
- Numeric Maven-style intervals and exact versions are supported, including alpha, beta and RC qualifiers and historical AE2 `rv3-beta-6`-style releases. Unrecognized ranges are not guessed. Jar-in-Jar payloads are inspected for legacy/Connector code, but version negotiation is left to NeoForge. Supply standalone local providers for required mod IDs the planner cannot resolve. Static metadata cannot establish optional side-specific integrations or actual class linkage across the full pack.

### Retain two private native library versions

For **pure Java implementation libraries**, build each consumer against a *relocated* copy of the version it needs. Use a source-level namespace change or configure your build's shading tool to relocate the definitions and references together. Put any library-private resources under that same namespace and update resource lookups. Keep notices/licenses and check the consumer's behavior. No pack update step rewrites a binary on your behalf.

A prepared consumer JAR can include `META-INF/mcmodup/private-libraries.json`:

```json
{
  "schema_version": 1,
  "libraries": [{
    "consumer": "alpha",
    "id": "bitcodec",
    "version": "1.0",
    "original_package": "old/codec",
    "source_sha256": "1111111111111111111111111111111111111111111111111111111111111111"
  }]
}
```

Replace the example SHA-256 with the reviewed library input's actual digest. In this example, the port must relocate `old/codec/...` to `mcmodup_private/alpha/bitcodec/v1_0/...` and use that package from its code. The namespace includes the native consumer ID, library ID and release (nonalphanumeric version characters become `_`). Another port `beta` can carry `v2_0` under `mcmodup_private/beta/bitcodec/`. Neither declares the private copy as a loader-wide mod dependency. The planner checks that relocated code exists, is referenced by its consumer, is not shared across JARs, has no remaining old-package class references, and does not link to Minecraft/loader classes or register Java services. It records the author-declared source hash; that is not proof of the library's release or functionality. Reflection, resource behavior and integration tests still need review. Content/registry mods, transformers, service providers and native libraries are not candidates for this treatment.

## Example: Thaumic Energistics with Applied Energistics 2

Use an actual 1.7.10 Thaumic Energistics JAR, e.g. [`1.1.3.0` for AE2 RV3](https://www.curseforge.com/minecraft/mc-mods/thaumic-energistics/files/2563527), and keep the original AE2 and Thaumcraft JARs available in the source for baseline evidence. Put these local **native** JARs under the target's `lib/`:

- [Thaumic Energistics: Reforged 1.1 (1.21.1)](https://github.com/Incoderr/thaumic-energistics-reforged/releases/tag/1.21.1) and [Thaumcraft 4: Reforged 1.1.3 (1.21.1)](https://github.com/Incoderr/thaumcraft-4-reforged/releases/tag/1.21.1). Use this matching Thaumcraft family, not a different port merely because it declares `thaumcraft`.
- [AE2 19.2.18 for NeoForge](https://github.com/AppliedEnergistics/Applied-Energistics-2/releases/tag/neoforge/v19.2.18), [GuideME 21.1.22](https://github.com/AppliedEnergistics/GuideME/releases/tag/v21.1.22), Curios `9.5.1+1.21.1` and TerraBlender `4.1.0.8`.

Write `{"appliedenergistics2":"ae2"}` to a separate `ids.json`, review the native JARs, and select only the original Thaumic Energistics with `--mod`. Its native required dependencies bring in AE2 and the matching Thaumcraft port. This is a real inter-mod API port, not old Thaumic Energistics pointed directly at a new AE2 JAR. For CB Multipart, review the three IDs together: `ForgeMultipart` → `cb_multipart`, `ForgeMicroblock` → `cb_microblock`, `McMultipart` → `cb_multipart_minecraft`. That still does not port other old add-ons using Multipart APIs.

## Verification and remaining limits

The pack tests cover nested `mods/` and `coremods/`, launcher-era Forge/Minecraft API evidence, two legacy versions bound to their exact consumers, shared and disjoint native constraints, local SHA-256 pins, and a fresh output that leaves both inputs unchanged. The [release smoke workflow](../.github/workflows/pack-compatibility.yml) also executes two relocated Java library versions and checks that each consumer reads its own resource.

In [the passing release smoke](https://github.com/mechwarriorpro/mcmodup/actions/runs/44342366654), the 1.7.10 inputs included Thaumic Energistics `1.1.3.0`, AE2 `rv3-beta-6`, Thaumcraft `4.2.3.5`, and Forge Multipart `1.2.0.345`. The updater staged the matching Reforged stack listed above, CB Multipart `3.5.0.155`, and CodeChickenLib `4.6.1.526` with a clean NeoForge `21.1.267` server runtime. The copied server reached its ready state and shut down cleanly. Separate GameTests using the same mod JARs verified that an AE2-backed cell preserved 37 units of fire essentia through serialization and reload, and that CB Microblock's item and diamond saw recipe registered.

No input or staged JARs were published. These checks do not establish compatibility with Thaumcraft Reload or other forks, full gameplay or asset parity, graphical client behavior, or migration of an existing world.

### Mixed original pack: Minechem, Voltz, Thaumcraft 4, GeoStrata, Mimicry and Random Things

The [mixed-pack workflow](../.github/workflows/mixed-legacy-pack.yml) pins the actual original archives and runs **one** `update-pack` plan on all eight, with CB Multipart explicitly included. The default run supplies no prebuilt replacements. The historical references are inspected without executing them or publishing any mod binaries. A separate opt-in run can use Legacy Connector as an oracle, not as an updater output. The selected filenames are:

| Minecraft | Archives |
| --- | --- |
| 1.6.4 | `Minechem-1.6.4-5.0.5.FINAL.jar`, `icbm-1.4.1.344.jar`, `atomicscience.jar`, `GeoStrata 1.6 v23b.zip`, `Mimicry 1.6.4_965_1.3.9_final.zip`, `Random_Things_v._1.9__MC_1.6.4_.jar` |
| 1.7.10 | `Minechem-1.7.10-5.0.5.394.jar`, `Thaumcraft-1.7.10-4.2.3.5.jar` |

Use `--mod mods/<version>/<filename>` once for each of the eight original archives and `--include-native cb_multipart --output-pack NEW_DIR --json`. The ICBM and Atomic Science JARs come from the exact Voltz 3.0.0c distribution. The former contains `ICBM`, `ICBM|Explosion` and `ICBM|Sentry`. The GeoStrata and DragonAPI V23b originals are paired in FTB's Monster 1.1.2 manifest.

**The eight-original pack remains blocked: 0 of 8 originals have verified full ports, and no partial pack was staged for that selection.** The two Minechem originals share the mod ID `minechem`, 223 class names under `minechem/`, and 118 paths under `assets/minechem/`. The [paired updater recipes](source-port-builds.md#both-exact-minechem-releases-linked-previews) select them by SHA-256 and build isolated namespaces.

**Verified subset on NeoForge 21.1.267:** [Both exact Minechem previews, the ICBM glass plate, and the Atomic Science thermometer](https://github.com/mechwarriorpro/mcmodup/actions/runs/44448853373/job/135307550328) passed eight source tests, stock dedicated boot, and 14 staged-JAR tests together with unchanged CB Multipart and CodeChickenLib. Multipart read the plate and thermometer redstone transitions. Selecting all eight with those same recipes still wrote nothing.

To reproduce the subset, combine the three recipe manifests with repeated `--source-ports` flags. Use a separate source directory with the four selected archives under their `mods/<version>/` paths and index the exact Voltz Universal Electricity and CalclaviaCore JARs under its `lib/`. Do not select the old libraries. Indexing these references avoids treating the copies of a few library classes inside Minechem FINAL as ICBM's dependency provider. Use a fresh `--output-pack`, `--allow-preview-ports`, and `--include-native cb_multipart` with both native control JARs already in the target.

The [exact Minechem Forge comparisons](https://github.com/mechwarriorpro/mcmodup/actions/runs/44377478998) confirmed different power, inventory and jam-recovery rules. The linked previews reproduce selected behavior; the .394 build decomposes only iron ingots. The [ICBM glass pressure plate](source-port-builds.md#voltz-glass-pressure-plate-executable-preview) and [Atomic Science thermometer](source-port-builds.md#voltz-atomic-science-thermometer-executable-preview) do not implement the rest of ICBM Core, its Explosion and Sentry modules, or Atomic Science's reactor and thermal systems. Both exact Voltz archives use CalclaviaCore and Universal Electricity APIs that still need native equivalents. The [Thaumcraft preview](source-port-builds.md#thaumcraft-4-reload-preview) does not establish full Frost parity or legacy save migration. GeoStrata, Mimicry and Random Things have no validated native ports from these exact releases.

**Separate native control:** CB Multipart `3.5.0.155` and CodeChickenLib `4.6.1.526` were copied byte-for-byte and booted on stock NeoForge `21.1.267`. A test-only server crafted two stone covers with the saw, checked multipart slot occupancy, and restored the item component and placed covers through serialization. No converted mods were loaded. This does not verify their interaction with microblocks, client rendering, or legacy saves.

If no native target exists, use a [reviewed source build](source-port-builds.md) or [recover MCP-named references and plan the port](mapped-decompilation.md). The partial `convert-jar` profiles are not drop-in replacements.
