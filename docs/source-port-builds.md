# Build native 1.21.1 JARs

Use `build-jars` for built-in ports, or supply `--source-ports TRUSTED.json` to `update-pack` or `pack-ui` for other reviewed source projects. These commands **compile new JARs**, rather than choose prebuilt replacements. They use the same [installed folder layout](native-pack-updates.md#inputs) and `mods/` swap (`-o`) or fresh instance (`--output-pack`) as the updater. Install Python 3.11+ and **JDK 21**; the paired build also requires Git on `PATH`. First-run Gradle builds need network access to the pinned projects' repositories.

No built-in recipe has full parity yet. `build-jars` requires `--allow-preview-ports`, leaves both input packs unchanged, and writes the new `mods/`, `update-report.json`, and `INSTALL.txt` outside them.

In source-build mode, selecting a base mod automatically includes its detected old add-ons. Every selected legacy release and add-on needs an exact SHA-256 recipe; an existing JAR with the same mod ID does not stand in for a missing source port. Repeat `--source-ports` to combine recipe sets. A source ZIP path stays relative to its own manifest, and duplicate original hashes are errors. Recipes build in dependency order. Required native libraries are taken from the installed packs where compatible. A library with its own exact recipe can be built if a unique local native JAR is not available. Old Forge libraries aren't passed through to the modern game.

**Trust boundary:** Recipes are shipped with the tool or supplied explicitly, not taken from `mods/`. Gradle source ZIPs, their wrappers, plugins, and mods under test execute code on your machine. Only provide recipes, sources and dependencies you have reviewed and trust. The temporary working directory is not a security sandbox. Read-only listing and planning do not download or execute sources. Do not publish third-party code, assets or your output JARs without the applicable rights.

## Both exact Minechem releases (linked previews)

Put **Minechem 1.6.4 5.0.5 FINAL** and **Minechem 1.7.10 5.0.5.394** in the two release folders shown below. Use the exact originals, not a similarly named recompilation. Install Minecraft 1.21.1 / NeoForge **21.1.267** in `NeoForgePack`. For the native control, put CB Multipart **3.5.0.155** and CodeChickenLib **4.6.1.526** in its `mods/` folder.

```sh
final=mods/1.6.4/Minechem-1.6.4-5.0.5.FINAL.jar
v394=mods/1.7.10/Minechem-1.7.10-5.0.5.394.jar
python3 -m mcmodup prepare-minechem-pair "LegacyPack/$final" "LegacyPack/$v394" \
  -o .mcmodup/MinechemRecipes
python3 -m mcmodup update-pack LegacyPack --target NeoForgePack \
  --mod "$final" --mod "$v394" --include-native cb_multipart \
  --source-ports .mcmodup/MinechemRecipes/minechem-pair.json --allow-preview-ports \
  --output-pack .mcmodup/MinechemPreview
```

The first command checks both fingerprints and prepares local source and recipes. It does not execute either original. At build time the updater copies the referenced catalog icons and rewrites only FINAL's `canUnjam` and .394's `pushQueue` from the exact binaries. FINAL checks for an empty output slot after a jam; .394 compacts existing output on intake. Typed helpers map legacy slots, fields and calls to native inventories and item stacks. Unmapped references block the build. Each release must pass its output-rule and saved-queue tests before either JAR is staged.

The two results have separate Java packages, mod IDs (`minechem_chemistry` and `minechem_chemistry_394`), items, block entities and assets. Each adds only its own blocks to the vanilla pickaxe tag; neither replaces it. There is no Connector or old Forge JAR in the output. The two native controls are copied unchanged. Both sources and artifacts are local derivatives; don't redistribute them without the appropriate permissions.

The [paired check](https://github.com/mechwarriorpro/mcmodup/actions/runs/44377479138/job/135060390070) passed two build-time GameTests per release. It booted the staged production JARs with the unchanged controls, then passed ten staged-JAR GameTests. The [exact Forge measurements](https://github.com/mechwarriorpro/mcmodup/actions/runs/44377478998) confirmed that an occupied matching slot doesn't clear FINAL's jam, while .394 compacts at intake and retains any excess in the trailing slot.

**Preview only.** Most original machines and integrations are missing; .394 only decomposes iron ingots. FE and modern item components are not legacy UE/RF or arbitrary legacy stack metadata, and this does not migrate saves or recreate legacy clients. These two builds do not unblock the eight-original selection.

## Voltz glass pressure plate (executable preview)

Use Python 3.11+, JDK 21, the **original `icbm-1.4.1.344.jar` from Voltz Server 3.0.0c**, and an installed **Minecraft 1.21.1 / NeoForge 21.1.267** instance. The source build matches the original SHA-256, not just the version or filename. For this preview, put only the original ICBM archive in the source `mods/1.6.4/` directory; it cannot replace ICBM for a legacy add-on.

```sh
python3 -m mcmodup prepare-icbm-plate -o .mcmodup/plate-recipe
python3 -m mcmodup update-pack LegacyPack --target NeoForgePack \
  --mod mods/1.6.4/icbm-1.4.1.344.jar \
  --source-ports .mcmodup/plate-recipe/icbm-plate.json --allow-preview-ports \
  --output-pack .mcmodup/PlatePreview
```

The first command writes a reviewable recipe and source ZIP, not a mod. The second compiles and tests the port, then writes `mods/icbm_glass_plate-1.4.1.344-preview.2.jar` to the new instance. It imports the block texture and the original two methods that update power and control the recheck delay. The adapter maps those methods' world and block calls to NeoForge; the JAR does not contain the original ICBM classes. A fresh world can craft the plate from two horizontal glass blocks or use `/give @s icbm_glass_plate:glass_pressure_plate`.

To include the tested native dependencies, install **CB Multipart 3.5.0.155** and **CodeChickenLib 4.6.1.526** under `NeoForgePack/mods/` and add `--include-native cb_multipart` to `update-pack`. In the [passing preview.2 comparison](https://github.com/mechwarriorpro/mcmodup/actions/runs/44375537405), the exact original ran on Voltz's Forge 9.11.1.965 with Java 7. Both sides tested horizontal glass crafting, no self-drop, item-triggered redstone and scheduled release. The preview rewrites the old numeric block-ID field read to a call to NeoForge's live registry. The fresh NeoForge server booted with both native JARs unchanged, and a staged-JAR GameTest called Multipart's signal lookup. NeoForge can load the plate without those two JARs as well. No graphical client comparison or old-save migration was performed.

Only the single glass pressure plate from Core is implemented. Explosion, Sentry and the rest of Core are absent; fresh worlds use a separate registry ID. The updater marks the JAR as a preview and won't use it to satisfy a dependency or to replace a complete ICBM installation. Keep the result local unless you have redistribution rights for the original code and texture.

## Thaumcraft 4 Reload (preview)

The [opt-in recipe](../mcmodup/templates/source-ports/thaumcraft4-reload-preview.json) compiles the unofficial [Thaumcraft 4 Reload alpha.24 source](https://github.com/acak1221/Thaumcraft-1.21.1/tree/1417e30fa04ce349a8d811d8f1b3d7515cc58643) locally, not its release JAR. You need:

- The exact **Thaumcraft 4.2.3.5** JAR in the old pack's `mods/`. The recipe matches its SHA-256, not its filename. First test with a clean target, and a selection without original add-ons that don't have native sources. The updater will flag such add-ons rather than skip them.
- A **Minecraft 1.21.1 / NeoForge 21.1.259 or later 21.1.x** installation under the target's `bin/`. Put the vetted NeoForge 1.21.1 **Architectury API 13.0.11** JAR in the target's `lib/` or `mods/`. The pinned source project uses its own Gradle dependencies for development; the staged runtime uses the JAR found in the pack.
- Permissions to make a local build. The [upstream attribution](https://github.com/acak1221/Thaumcraft-1.21.1/blob/1417e30fa04ce349a8d811d8f1b3d7515cc58643/THIRD_PARTY_NOTICES.md) distinguishes new code from Azanor's original code and assets. Keep the staged JAR local.

If the original JAR has its release filename, run:

```sh
python3 -m mcmodup build-jars LegacyPack --target NeoForgePack \
  --mod mods/Thaumcraft-1.7.10-4.2.3.5.jar --allow-preview-ports \
  -o .mcmodup/thaumcraft-preview
```

Use `--json` for the build report on stdout. `--keep-recipe NEW_DIR` retains the generated recipe and exact patched source ZIP for inspection or a later `pack-ui` or `update-pack` invocation. The standalone `build-jars` route verifies the upstream source digest, applies the changes in [`thaumcraft_parity.py`](../mcmodup/thaumcraft_parity.py), and builds `0.1.0-alpha.24-mcmodup.1`. The direct upstream recipe and the Tinkerer pair use different source inputs. Use `--source-zips DIR` to read exact upstream archives from disk instead of codeload (`<project>-<commit>.zip`; see [the base pin](../mcmodup/templates/source-ports/thaumcraft4-reload-preview.json) and [the six pair pins](../mcmodup/tinkerer_computercraft.py)). This does not make Gradle dependency resolution offline.

**Exact asset accounting:** The [independent audit](https://github.com/mechwarriorpro/mcmodup/actions/runs/44301870962) matches all **1,091 tracked paths** of Thaumcraft 4.2.3.5, including the old root logo. The recipe now requires each path to be byte-identical, relocated, or mapped to a specific adapted resource in the **built JAR**. The Warp effects use native shader replacements. The English and Russian original `.lang` files and experimental shader samples are kept as legacy resources, not loaded instead of the authored translations or active shaders. Twenty other locales are converted. The report lists malformed lines and duplicate keys rather than inferring translations for renamed keys. Asset accounting is not proof that the original appearance or every translation was reproduced.

**Verified on NeoForge 21.1.267:** [Production-JAR CI for the patched standalone build](https://github.com/mechwarriorpro/mcmodup/actions/runs/44366504941/job/135021535112) passed 539 source GameTests and five tests against the exact original JAR. The staged build contained 1,037 byte-identical or relocated paths, 54 mapped adaptations, and no unmapped tracked asset paths. In independent runtime tests crucible placement created its block entity on a dedicated server. The staged JAR also opened the Thaumonomicon in a fresh client world, processed all four Warp post chains before and after a resource reload, cleared them, and saved the world. No Connector was loaded.

**Exact-original comparison:** The [Forge oracle](https://github.com/mechwarriorpro/mcmodup/actions/runs/44364985694) crafted a metadata-1 golden apple and measured `lucrum=64`, `messis=1`, `metallum=64`, `praecantatio=4`, and `sano=8`. It also measured a fresh aura node deriving its identity from its coordinates, then preserving that identity when moved or loaded from saved NBT. The standalone changes restore the default apple aspect base without adding a vanilla crafting recipe, and derive new node identities from their positions. The staged tests exercise the apple and overworld node cases. This does not port the historical world format or change existing native UUIDs stored in saves.

**Still preview-only:** The [upstream deviations](https://github.com/acak1221/Thaumcraft-1.21.1/blob/1417e30fa04ce349a8d811d8f1b3d7515cc58643/docs/audit/KNOWN_DEVIATIONS.md) include different font rendering, shader-pack compatibility gaps, and missing or unverified behavior; they describe the upstream build before these standalone changes. Original add-on binaries need native ports. Integrated accessory slots do not guarantee compatibility with Baubles add-ons. There is no old save migration. If your filesystem cannot hold different `Bore.png` and `bore.png` files, build on a case-sensitive filesystem (such as the WSL Linux filesystem), not on a Windows-mounted drive.

## Thaumic Tinkerer paired preview

This matches **GTNH Thaumic Tinkerer 2.13.0** for Minecraft 1.7.10, not every Thaumic Tinkerer release. Put that [original JAR](https://github.com/GTNewHorizons/ThaumicTinkerer/releases/tag/2.13.0) alongside the exact original Thaumcraft 4.2.3.5 JAR in `LegacyPack/mods/`. Use a clean 1.21.1 / NeoForge target and the same pack-local Architectury as above. Do not substitute the unmodified Reload alpha.24 release JAR: the pair needs the [post-release obsidian-tile fix](https://github.com/acak1221/Thaumcraft-1.21.1/commit/299a9c01cc4904597381f96cc4bbb8cb9ff667de).

Select the **base JAR** with the same command as above (use a fresh output path). When the updater finds the exact add-on, the builder compiles both in dependency order, with optional CC: Tweaked and Ender Storage support built in. It applies reviewed patches to six hash-pinned source archives and uses pack-local Architectury. It does not need a separate recipe preparation command or a prebuilt Thaumcraft or Tinkerer JAR. Unsupported detected add-ons block the build rather than being dropped.

- **Bedrock** uses the original world/chunk seed and the three discarded draws of [`ChunkProviderBedrock.populate`](https://github.com/GTNewHorizons/ThaumicTinkerer/blob/28f4def938e80d7a0b6f433c2b84300dfb7c35ce/src/main/java/thaumic/tinkerer/common/dim/ChunkProviderBedrock.java), rather than 1.21.1's feature seed. Geometry, density, and blacklist still run through the native world-generation feature. Modern ore tags can resolve to different blocks from the old OreDictionary, and overlapping clusters are still sensitive to population order. The client uses the original day-independent base fog, zero cloud height, and full-bright lightmap input. Vanilla's later color processing and modern effects such as Darkness still apply.
- **Imbued Fire** uses the NeoForge flammability callback for other mods' blocks, passing the same spread direction as the [original](https://github.com/GTNewHorizons/ThaumicTinkerer/blob/28f4def938e80d7a0b6f433c2b84300dfb7c35ce/src/main/java/thaumic/tinkerer/common/block/fire/BlockFireBase.java). The fire's transmutation rules take precedence, and the vanilla table keeps the legacy values.

The paired recipe checks the upstream texture/locale migration table against the exact original JAR. The [pre-ComputerCraft production-JAR run](https://github.com/mechwarriorpro/mcmodup/actions/runs/44303585905) passed 543 base and 371 add-on source GameTests and found **312 byte-identical or relocated paths, seven adapted paths, and no unmapped paths** of the add-on's 319 tracked assets. It exercised the Osmotic Enchanter infusion with normally crafted obsidian tiles and opened the Enchanter and entered Bedrock on a fresh client.

The [add-on's limitations and attribution](https://github.com/acak1221/Thaumic-Tinkerer-1.21.1/blob/c2e67665446ffada85ec9342384f578875cabb12/NOTICE.md) include omitted legacy integrations and non-commercial, share-alike terms. Keep the outputs local; neither CI preview publishes third-party JARs.

### Optional ComputerCraft integration

The pair uses the reviewed [Tinkerer ComputerCraft changes](https://github.com/acak1221/Thaumic-Tinkerer-1.21.1/tree/35c2fad848dd095e65263a6a163c0c964d021c00) and the [Reload machine/golem API changes](https://github.com/acak1221/Thaumcraft-1.21.1/commit/316897d63fde06f10313ad6162426a906b636d77) without dropping the Bedrock and obsidian-tile fixes.

1. To play with computers, put [CC: Tweaked 1.116.1 for NeoForge 1.21.1](https://modrinth.com/mod/cc-tweaked/version/SQI2qmh5) (`cc-tweaked-1.21.1-forge-1.116.1.jar`) in `NeoForgePack/mods/`. Do not install legacy ComputerCraft or the Fabric release. The pair also runs without CC: Tweaked; it is not staged if it is not installed, though the source build still tests the integration.
2. Run `build-jars` as above, selecting the base. To retain the recipe and source ZIPs for the runtime harness below, add `--keep-recipe .mcmodup/tinkerer-recipe`.
3. In a fresh world, use the research **Computercraft Peripherals** for the Lua commands. Connect a computer to an Aspectalyzer, Dynamism Tablet, attractor, or another supported block; craft a turtle with the Fume Dissipator to give it the tool. CC: Tweaked and its nested libraries are **not** bundled in Thaumic Tinkerer.

The backport restores reading the label of an empty fillable or void jar: `getAspects()` returns the aspect, while `getAspectCount()` still reports zero. Tinkerer `0.1.0-alpha.7-mcmodup.cc.4` requires Reload `0.1.0-alpha.24-mcmodup.cc.1`. Other differences described in the [upstream behavior notes](https://github.com/acak1221/Thaumic-Tinkerer-1.21.1/blob/35c2fad848dd095e65263a6a163c0c964d021c00/docs/deviations/ComputerCraft.md) remain, including namespaced golem marker dimensions and changed argument errors. OpenComputers, IC2 and other old integrations are not restored.

**Production-JAR verification:** The [paired run on NeoForge 21.1.267](https://github.com/mechwarriorpro/mcmodup/actions/runs/44323611644) passed 549 Thaumcraft and 385 Tinkerer source GameTests, plus five exact original-JAR checks. The built JARs had no unmapped paths out of 1,091 tracked Thaumcraft assets and 319 Tinkerer assets. The dedicated test server passed 20 tests with both optional integrations installed, including 14 ComputerCraft integration cases, Ender Rift binding and casting, Imbued Fire, and Bedrock terrain. A fresh graphical client rendered the computer screen and the Fume Dissipator model on a crafted and placed turtle. The same port JARs passed the five baseline server checks and the graphical smoke test with both optional integrations removed. No original or built mod JARs were uploaded.

### Optional Ender Storage integration

Add [Ender Storage 2.13.0.191](https://modrinth.com/mod/ender-storage/version/iDxzdaLT) and [CodeChicken Lib 4.6.0.521](https://modrinth.com/mod/codechicken-lib/version/tQEcfeph) for NeoForge 1.21.1 to the target's `mods/` before building. These JARs are not bundled in either port. Without them, the Ender Rift opens the vanilla Ender Chest.

With Ender Storage present, sneak-use your wand's Ender Rift on an Ender Storage chest to link its colours and owner, or on obsidian to clear the link. Neither action spends vis. Use the focus normally to open the linked inventory remotely; casting costs 100 centivis each of Ordo and Perditio before the usual discounts. The research uses the original Ender Storage help text. The focus stores the native owner's UUID, not the legacy player name; it follows Ender Storage's anarchy-mode rule for another owner's chest. This does not migrate old inventories or wand links.

## Test the emitted JARs

The verification harness copies the staged `mods/` into separate temporary NeoForge 21.1.267 instances. It runs the crucible GameTest on the production JAR, not the port's source tree. In the pair it also checks the Osmotic Enchanter infusion's five crafted obsidian-tile inputs. The client harness launches the staged JARs in a fresh creative world, uses the Thaumonomicon, checks the four active Warp post chains before and after resource reload and removal, and saves the world. For the pair the server fixture also compares every block in two generated Bedrock chunks against the legacy population seed with current ore tags and verifies that Imbued Fire burns only the exposed side accepted by a direction-sensitive test block. The client opens the Osmotic Enchanter screen, enters Bedrock through its portal, checks fog and cloud height, and examines the rendered lightmap at midnight. These checks use supplied items and blocks, not full survival progression or pixel-for-pixel comparisons.

From the checkout, with JDK 21 and a graphical display (or Xvfb on Linux):

```sh
PYTHONPATH=.:tests python3 -m thaumcraft_pack_smoke .mcmodup/thaumcraft-preview --client
PYTHONPATH=.:tests python3 -m thaumcraft_pack_smoke .mcmodup/staged-tinkerer-pair --client --with-tinkerer
PYTHONPATH=.:tests python3 -m thaumcraft_pack_smoke .mcmodup/staged-tinkerer-computercraft --client --with-tinkerer \
  --computercraft-source .mcmodup/tinkerer-recipe/tinkerer-neoforge-source.zip
```

The third run assumes the exact CC: Tweaked release was staged. It checks the peripherals, actual Lua calls, the craftable turtle tool and gas removal on a dedicated server. With `--client` it also exercises a computer GUI and the placed turtle's upgrade model. Add `--with-ender-storage` if the exact Ender Storage and CodeChicken Lib releases were staged. The server fixture tests private vs. shared chest inventories, a saved wand link, vis payment, unlinking for free, and the research page. The harness repeats the baseline tests with exactly the same port JARs and the optional mods removed. Without `--client`, all commands only run the dedicated server checks. These tests are separate from the updater, do not change either input pack or the staged JARs, and do not assert complete parity.

## Coverage by original mod

| Original | What's available | Not a full port because… |
|---|---|---|
| Thaumcraft 4.2.3.5 | Exact native source-build preview, plus the paired Tinkerer preview | Visual, localization, survival, multiplayer and compatibility gaps remain, even with complete asset accounting. |
| GTNH Thaumic Tinkerer 2.13.0 | Exact native add-on preview, with optional CC: Tweaked and Ender Storage runtime support | OpenComputers and other legacy integrations, some behaviors and some effects are still missing. |
| Minechem 1.6.4 5.0.5 FINAL | [Linked updater preview](#both-exact-minechem-releases-linked-previews), including the adapted Microscope and Chemist's Journal | Not all machines, recipes, effects, worldgen, graphics or integrations have been ported. |
| Minechem 1.7.10 5.0.5.394 | [Linked updater preview](#both-exact-minechem-releases-linked-previews), separate from FINAL | Only an iron decomposer and catalog; no other machines or original integrations. |
| ICBM 1.4.1.344 | [Glass pressure plate preview](#voltz-glass-pressure-plate-executable-preview) | Only one Core block; not the rest of Core, Explosion or Sentry. |
| Atomic Science 1.2.0 | [Exact Voltz binary and dependency evidence](usage-examples.md#atomic-science-120-and-icbm-141344-forge-164-input-examples) | No reviewed native build for the original and its required old dependency APIs. |

## What the source-build test covers

The [Retro Ruby CI smoke test](https://github.com/mechwarriorpro/mcmodup/actions/runs/44294784277) builds the fixture base and automatically selected add-on from source ZIPs, checks the original texture and adapted locale, and loads their staged production JARs with the actual pack-local library. The add-on calls the base and library APIs to set an item's stack size, and a NeoForge GameTest checks that stack size without Connector. This verifies the build path, not a third-party mod's full parity.

Try the same checklist in read-only mode without a JDK or third-party mod files. Choose a new sample directory, then select **Retro Ruby** to see its add-on and library in the plan:

```sh
sample=.mcmodup/source-demo
mkdir -p .mcmodup
PYTHONPATH=.:tests python3 -m source_pack_smoke --demo "$sample"
python3 -m mcmodup pack-ui "$sample/Legacy source-build example" \
  --target "$sample/NeoForge 1.21.1 example" \
  --source-ports "$sample/reviewed-source-ports.json"
```

## Supply a completed port or add-on source recipe

Keep a separate recipe for each original build, including add-ons. Start with a reviewed standalone **NeoForge 1.21.1 Gradle source project**, not a legacy project whose only change is a version number. Author and test the native implementation of each feature first; the recipe doesn't translate arbitrary old Java or bytecode. The worked, executable toy base/add-on example is [`tests/source_pack_smoke.py`](../tests/source_pack_smoke.py).

1. Create a clean ZIP of the source project and wrapper, excluding prebuilt outputs, worlds, credentials and caches. The wrapper must declare `distributionSha256Sum`. Reference the ZIP by a path relative to the recipe file, or use an immutable GitHub codeload commit ZIP. Record both archive SHA-256 fingerprints (legacy JAR and source ZIP). If your GitHub ZIP has a top-level directory, specify `source_root` to strip it.
2. Put the recipes in `{"schema_version": 1, "ports": [...]}`. For each row, include `legacy_sha256`, `source_sha256`, exactly one `source_zip` or `source_url`, and the exact output `ids` (map of mod IDs to versions). The IDs must match the original declarations, their reviewed `mcmodup-updates.json` aliases, or the recipe's SHA-bound `legacy_ids` map described below. Declare the target `neoforge` range and every non-platform `dependencies` row (`id`, `range`, and `type` for optional/incompatible dependencies), matching the JAR metadata.
3. Set `artifact` to the output JAR path relative to the ZIP's project root, `tasks` to the Gradle build and NeoForge GameTest tasks, and `game_report` to the **fresh** JUnit XML generated by that test run. Set a nonempty `features` map from reviewed behavior to exact GameTest case names and a representative `minimum_tests` count. In add-on tests, run actual code using the base mod and any local dependency APIs. Keep the test harness out of the production artifact. If the project checks the original binary as a reference, use `original_jar_checks` with its root `jar_name`, fresh JUnit XML `report`, and exact `cases` expected to pass. The updater copies only the matched original JAR into scratch, refuses skipped or missing cases, and checks the JAR is not embedded verbatim in the built mod. This does not launch the old mod.
4. The updater passes `-PmcmodupTargetNeoForge` (and, if given, `target_property` under the project's preferred name) and `-PmcmodupDependencyDir` with copies of resolved dependency JARs, including earlier source builds. Use them in the source project's build configuration. For example, the ModDevGradle fixture uses `implementation fileTree(dir: providers.gradleProperty('mcmodupDependencyDir').get(), include: ['*.jar'])` for its add-on, so the chosen local library and freshly built base are used in compilation and the GameTest run.
5. The asset check includes `assets/<namespace>/…` and legacy resource layouts, plus recognized file types (for example root-level images). Same-path byte-identical assets need no rule. `asset_map` can specify a new `path` for a relocated byte-identical asset; for a deliberate conversion such as `.lang` to `.json`, also pin the adapted asset's `sha256`. `import_assets` can copy selected original files into `src/main/resources/<expected-asset-path>` in the scratch project. It won't overwrite project files. `locale_imports` maps an original `assets/<namespace>/lang` folder to the corresponding directory under a module's `src/main/resources`. It converts simple UTF-8 `key=value` files if the native JSON locale does not already exist, preserves the legacy keys, and verifies the generated JSON in the JAR. It cannot guess renamed keys. Unsupported lines or duplicates block reviewed builds and are reported for previews. Review actual models, translations, sound events, and their renderer behavior separately: archive coverage doesn't test how an asset is used.
6. Use `status: "reviewed"` only for a fully reviewed scope; the builder rejects missing or unverified asset paths and failing, skipped, or missing required GameTests. If there are gaps, use `status: "preview"` and describe them in `limitations`. Preview builds require `--allow-preview-ports` and still have to compile and pass the declared GameTests. Use `require_asset_coverage: true` on a preview to reject unmapped tracked paths without claiming gameplay parity.

`update-report.json` distinguishes the original and source fingerprints, built files, dependencies supplied to the builds, named server GameTests, and retained/adapted/unmapped assets. The updater does not certify feature completeness from self-declared metadata, or claim the staged pack is playable in every world or with every optional integration. Verify the production artifacts in a fresh client and server, then validate the actual pack's add-on behavior before replacing the target `mods/`. Neither input pack is modified and no JAR is staged if a build check fails. Legacy Connector is not run or included.

### Split identities and import selected JVM methods

- **`legacy_ids`** maps all recognized original mod IDs (lowercase) to the `ids` declared for this specific `legacy_sha256`. Every native ID must appear exactly once. For example, two versions that both used `minechem` need two exact recipes and two different native IDs if both are to be loaded. This is supported only in a fresh `--output-pack`. The builder rejects shared Java packages, shared `assets/` or `data/` namespaces, and embedded JARs between split versions. The only shared vanilla resource allowed is an append-only `minecraft:mineable/pickaxe` block tag listing each JAR's own IDs. These checks do not migrate registry IDs in old worlds; cross-version add-ons still need native code and dependencies of their own.
- **`method_imports`** is an explicit adapter contract, not a general remapper. Each group names an original `owner`, a generated `output` under `mcmodup_kernel/<native-id>/`, a `types` map to local adapter classes, a `methods` map of exact original JVM symbols to new static method names, and a `members` map from used legacy fields and calls to matching public adapter members. Class initializers, constructors, and the old superclass are not copied. Unsupported instructions, missing bindings, failed GameTests, or a changed generated class block staging.
- **`redirects`** in a method group maps opcode names (`getfield`, `putfield`, `getstatic`, `putstatic`, `invokevirtual`, `invokestatic`) to `{original_symbol: native_helper_symbol}` maps. A rewritten instruction calls a public static helper in the port's kernel package. The helper must consume the same mapped operands and return the same type; an instance receiver becomes the first argument. The JAR verifier checks that the helper exists and is callable. Unused rules or an unsupported reference are errors, not requests to generate stubs. See the generated Minechem and ICBM recipes for examples.
- A recipe with `method_imports`, or with `null` for an unimplemented legacy ID, must use `status: "preview"`, document the omissions, and target a fresh output pack. It cannot satisfy another mod's dependency. The JAR carries `META-INF/mcmodup/linked-methods.json` with the original, generated class and adapter identities. A matching hash or a passing GameTest for one feature is not evidence of full-mod parity.
