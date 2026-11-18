# Build native 1.21.1 ports from the updater

Use `--source-ports TRUSTED.json` on `update-pack` or `pack-ui` to **build new JARs** rather than match prebuilt replacements. The arguments, [installed folder layout](native-pack-updates.md#folder-layout), and output `mods/` swap are otherwise the same. Install Python 3.11+ and **JDK 21**. Gradle needs access to the reviewed projects' build repositories on a first build.

In source-build mode, selecting a base mod automatically includes its detected old add-ons. Every selected legacy release and add-on needs an exact SHA-256 recipe; an existing JAR with the same mod ID does not stand in for a missing source port. Recipes build in dependency order. Required native libraries are taken from the installed packs where compatible. A library with its own exact recipe can be built if a unique local native JAR is not available. Old Forge libraries aren't passed through to the modern game.

**Trust boundary:** The recipe is an explicit input, not a script found in `mods/`. Gradle source ZIPs, their wrappers, plugins, and mods under test execute code on your machine. Only provide recipes, sources and dependencies you have reviewed and trust. The temporary working directory is not a security sandbox. Read-only listing and planning do not download or execute sources. Do not publish third-party code, assets or your output JARs without the applicable rights.

## First real-mod recipe: Thaumcraft 4 Reload (preview)

The [opt-in recipe](../examples/thaumcraft4-reload-preview.json) compiles the unofficial [Thaumcraft 4 Reload alpha.24 source](https://github.com/acak1221/Thaumcraft-1.21.1/tree/1417e30fa04ce349a8d811d8f1b3d7515cc58643) locally, not its release JAR. You need:

- The exact **Thaumcraft 4.2.3.5** JAR in the old pack's `mods/`. The recipe matches its SHA-256, not its filename. First test with a clean target, and a selection without original add-ons that don't have native sources. The updater will flag such add-ons rather than skip them.
- A **Minecraft 1.21.1 / NeoForge 21.1.259 or later 21.1.x** installation under the target's `bin/`. Put the vetted NeoForge 1.21.1 **Architectury API 13.0.11** JAR in the target's `lib/` or `mods/`. The pinned source project uses its own Gradle dependencies for development; the staged runtime uses the JAR found in the pack.
- Permissions to make a local build. The [upstream attribution](https://github.com/acak1221/Thaumcraft-1.21.1/blob/1417e30fa04ce349a8d811d8f1b3d7515cc58643/THIRD_PARTY_NOTICES.md) distinguishes new code from Azanor's original code and assets. Keep the staged JAR local.

```sh
python3 -m mcmodup pack-ui LegacyPack --target NeoForgePack \
  --source-ports examples/thaumcraft4-reload-preview.json --allow-preview-ports \
  -o .mcmodup/thaumcraft-preview
```

Check Thaumcraft and review the plan, then click **Build and stage mods**. The equivalent command-line build, if using the original filename:

```sh
python3 -m mcmodup update-pack LegacyPack --target NeoForgePack \
  --source-ports examples/thaumcraft4-reload-preview.json --allow-preview-ports \
  --mod mods/Thaumcraft-1.7.10-4.2.3.5.jar -o .mcmodup/thaumcraft-preview
```

This recipe checks compilation, NeoForge GameTests (including research, crucible, infusion, golems, taint and the Outer Lands), and the emitted JAR's mod IDs, dependency declarations and assets. It also converts extra original UTF-8 `.lang` files to modern locale JSON without overwriting the port's authored translations. That preserves the old keys but does not localize all renamed keys. The [first independent CI pass](https://github.com/mechwarriorpro/mcmodup/actions/runs/44294552551) built alpha.24, passed its NeoForge GameTests, then loaded the staged production JAR with the pack's Architectury in a separate NeoForge smoke test without Connector. The recipe also supplies the exact original JAR for five static mob/spawn unit checks; these are separate from the server GameTests. It remains **preview-only**. The [known deviations](https://github.com/acak1221/Thaumcraft-1.21.1/blob/1417e30fa04ce349a8d811d8f1b3d7515cc58643/docs/audit/KNOWN_DEVIATIONS.md) include unverified client and multiplayer behavior. Original add-on binaries need native ports; integrated accessory slots are not a promise of compatibility with every Baubles add-on. No old save migration. On a filesystem that cannot hold different `Bore.png` and `bore.png` files from the pinned source, build on a case-sensitive filesystem (such as the WSL Linux filesystem), not on a Windows-mounted drive.

| Original | What's available | Not a full port because… |
|---|---|---|
| Thaumcraft 4.2.3.5 | Exact native source-build preview above | Asset mapping, client and multiplayer parity, and the actual add-on set are not fully verified. |
| Minechem 1.6.4 5.0.5 FINAL | [Original tagged source](https://github.com/iopleke/Minechem/tree/1.6.4-5.0.5-FINAL) and the partial chemistry profile | Not all machines, recipes, effects, worldgen, graphics or integrations have been ported. |
| Atomic Science 1.6.4 and ICBM 1.6.4 | [Exact Voltz archives and dependency audits](usage-examples.md#atomic-science-120-and-icbm-141344-forge-164-input-examples) | No reviewed native source build for these originals or their required old dependency APIs. Later mods with similar features aren't replacements. |

## What the source-build test covers

The [Retro Ruby CI smoke test](https://github.com/mechwarriorpro/mcmodup/actions/runs/44294784277) builds the fixture base and automatically selected add-on from source ZIPs, checks the original texture and adapted locale, and loads their staged production JARs with the actual pack-local library. The add-on calls the base and library APIs to set an item's stack size, and a NeoForge GameTest checks that stack size without Connector. This verifies the build path, not a third-party mod's full parity.

## Supply a completed port or add-on source recipe

Keep a separate recipe for each original build, including add-ons. Start with a reviewed standalone **NeoForge 1.21.1 Gradle source project**, not a legacy project whose only change is a version number. Author and test the native implementation of each feature first; the recipe doesn't translate arbitrary old Java or bytecode. The worked, executable toy base/add-on example is [`tests/source_pack_smoke.py`](../tests/source_pack_smoke.py).

1. Create a clean ZIP of the source project and wrapper, excluding prebuilt outputs, worlds, credentials and caches. The wrapper must declare `distributionSha256Sum`. Reference the ZIP by a path relative to the recipe file, or use an immutable GitHub codeload commit ZIP. Record both archive SHA-256 fingerprints (legacy JAR and source ZIP). If your GitHub ZIP has a top-level directory, specify `source_root` to strip it.
2. Put the recipes in `{"schema_version": 1, "ports": [...]}`. For each row, include `legacy_sha256`, `source_sha256`, exactly one `source_zip` or `source_url`, and the exact output `ids` (map of mod IDs to versions). The IDs must match the original declarations or their reviewed `mcmodup-updates.json` aliases. Declare the target `neoforge` range and every non-platform `dependencies` row (`id`, `range`, and `type` for optional/incompatible dependencies), matching the JAR metadata.
3. Set `artifact` to the output JAR path relative to the ZIP's project root, `tasks` to the Gradle build and NeoForge GameTest tasks, and `game_report` to the **fresh** JUnit XML generated by that test run. Set a nonempty `features` map from reviewed behavior to exact GameTest case names and a representative `minimum_tests` count. In add-on tests, run actual code using the base mod and any local dependency APIs. Keep the test harness out of the production artifact. If the project checks the original binary as a reference, use `original_jar_checks` with its root `jar_name`, fresh JUnit XML `report`, and exact `cases` expected to pass. The updater copies only the matched original JAR into scratch, refuses skipped or missing cases, and checks the JAR is not embedded verbatim in the built mod. This does not launch the old mod.
4. The updater passes `-PmcmodupTargetNeoForge` (and, if given, `target_property` under the project's preferred name) and `-PmcmodupDependencyDir` with copies of resolved dependency JARs, including earlier source builds. Use them in the source project's build configuration. For example, the ModDevGradle fixture uses `implementation fileTree(dir: providers.gradleProperty('mcmodupDependencyDir').get(), include: ['*.jar'])` for its add-on, so the chosen local library and freshly built base are used in compilation and the GameTest run.
5. The asset check starts from every `assets/<namespace>/…` file in the exact legacy JAR. Same-path byte-identical assets need no rule. `asset_map` can specify a new `path` for a relocated byte-identical asset; for a deliberate conversion such as `.lang` to `.json`, also pin the adapted asset's `sha256`. `import_assets` can copy selected original files into `src/main/resources/<expected-asset-path>` in the scratch project. It won't overwrite project files. `locale_imports` maps an original `assets/<namespace>/lang` folder to the corresponding directory under a module's `src/main/resources`. It translates simple UTF-8 `key=value` files if the native JSON locale does not already exist, preserves the legacy keys, and verifies the generated JSON in the JAR. It cannot guess renamed keys. Unsupported lines or duplicates block reviewed builds and are reported for previews. Review actual models, translations, sound events, and their renderer behavior separately: archive coverage doesn't test how an asset is used.
6. Use `status: "reviewed"` only for a fully reviewed scope; the builder rejects missing or unverified asset paths and failing, skipped, or missing required GameTests. If there are gaps, use `status: "preview"` and describe them in `limitations`. Preview builds require `--allow-preview-ports` and still have to compile and pass the declared GameTests.

`update-report.json` distinguishes the original and source fingerprints, built files, dependencies supplied to the builds, named server GameTests, and retained/adapted/unmapped assets. The updater does not certify feature completeness from self-declared metadata, or claim the staged pack is playable in every world or with every optional integration. Verify the production artifacts in a fresh client and server, then validate the actual pack's add-on behavior before replacing the target `mods/`. Neither input pack is modified and no JAR is staged if a build check fails. Legacy Connector is not run or included.
