# Legacy Connector translation reference

The [native pack updater](native-pack-updates.md) does not invoke or bundle Connector. These tests are separate evidence about how the originals behave, not instructions for the native update path.

[Legacy Connector 0.5.0](https://github.com/kylepitcock/Legacy-Connector/releases/tag/v0.5.0) is a runtime translator for **Minecraft 1.21.1 on NeoForge**, not a standalone JAR exporter. It covers our three input versions: 1.6.4 and 1.7.10 are beta; 1.5.2 is alpha. [Upstream builds 0.5.0](https://github.com/kylepitcock/Legacy-Connector/tree/062c6e8d902083abae3adae2e856a657c7560ebb) against NeoForge `21.1.258`; the mcmodup checks below use our target, `21.1.267`.

On launch, Connector translates original mod bytecode against its legacy API implementation, then connects the old behavior to the modern game. The 1.6.4 and 1.5.2 layers reuse the 1.7.10 layer. It also handles some library substitutions, such as Baubles → Curios when Curios is installed. An unimplemented API can be replaced by a placeholder, so translating, linking, starting a world, and playing a feature are separate tests.

The translated cache **needs Connector**. [Its loader rejects cached derivatives placed directly in `mods/`](https://github.com/kylepitcock/Legacy-Connector/blob/062c6e8d902083abae3adae2e856a657c7560ebb/docs/wiki/Cache-and-Redistribution.md). Neither the cache nor the original files should be committed or redistributed without rights. Connector's [MIT license](https://github.com/kylepitcock/Legacy-Connector/blob/062c6e8d902083abae3adae2e856a657c7560ebb/LICENSE) covers its own code, not the legacy mods.

## mcmodup test results

The [Connector sanity run](https://github.com/mechwarriorpro/mcmodup/actions/runs/44286564230) passed with the released Connector 0.5.0 JAR and NeoForge `21.1.267`. The offline checker reported `links` for every JAR in the three sets below, with **zero unresolved references and zero verifier failures**. It still synthesized three members for ICBM and two classes for Calclavia Core. These placeholders can link without implementing the original behavior.

| Original inputs | Offline checker | Disposable server on NeoForge `21.1.267` |
| --- | --- | --- |
| Thaumcraft 4.2.3.5 + Baubles 1.0.1.10 | Both linked, no synthesized classes or members. | **2/2 passed.** Verified the loaded Thaumcraft version, Thaumonomicon and entity registration, and a placed crucible accepting and storing 1,000 mB of water via the fluid capability. |
| Minechem 5.0.5 FINAL + UE 3.1.0.74 from Voltz | Both linked, no synthesized classes or members. | Not run; this does not verify machine ticks or fix the upstream crash. |
| Voltz 3.0.0c: Atomic Science 1.2.0, ICBM 1.4.1.344, Calclavia Core 1.2.0.6-main, UE 3.1.0.74 | All four linked, with the ICBM and Calclavia Core placeholders noted above. | Not run; no gameplay claim for either mod. |

The Thaumcraft checks do not cover clients, research, heat or recipes in the crucible, or save/reload. No legacy saves were used, and no originals or Connector caches were uploaded. These checks are separate from the [native port tests](https://github.com/mechwarriorpro/mcmodup/actions/runs/44286564296).

To rerun the same checks, manually dispatch [the Connector workflow](../.github/workflows/connector-sanity.yml), or run its driver with JDK 21, Gradle network access, and private copies of the exact files:

```sh
PYTHONPATH=.:tests python3 -m connector_sanity \
  --connector .mcmodup/legacyconnector-0.5.0.jar \
  --thaumcraft .mcmodup/Thaumcraft-1.7.10-4.2.3.5.jar \
  --baubles .mcmodup/Baubles-1.7.10-1.0.1.10.jar \
  --minechem .mcmodup/Minechem-1.6.4-5.0.5.FINAL.jar \
  --voltz .mcmodup/Voltz_Server_v3.0.0c.zip
```

Use `--phase links` for offline results only or `--phase game` for the Thaumcraft server tests only. The offline check reports unresolved references and placeholders rather than treating either count as a gameplay verdict.

## Upstream results

These are results from the Connector project, not additional mcmodup runtime tests. See the [pinned compatibility entries](https://github.com/kylepitcock/Legacy-Connector/blob/062c6e8d902083abae3adae2e856a657c7560ebb/docs/data/mods.json) and [gameplay test log](https://github.com/kylepitcock/Legacy-Connector/blob/062c6e8d902083abae3adae2e856a657c7560ebb/docs/wiki/Gameplay-Test-Log.md#thaumcraft-4235-headless-server-audit).

| Example | What the evidence says |
| --- | --- |
| **Thaumcraft 4.2.3.5 (1.7.10)** | Strongest runtime candidate. Upstream tested this release's original research, wands, essentia, crucible, infusion, and some golem behavior. That is substantially more than our native aspect-only JAR, but not a guarantee about every feature, add-on, or old save. |
| **Minechem 5.0.5 FINAL (1.6.4)** | Upstream's entry is labeled `5.05.101`, not confirmed to be the same binary. The microscope worked; decomposer and synthesizer screens opened but machine ticks failed because their UE 3.0.0.1-dev lacked `Vector3.translate(ForgeDirection)`. The [5.0.5 FINAL build settings](https://github.com/iopleke/Minechem/blob/1.6.4-5.0.5-FINAL/build.properties) call for UE `3.1.+`. |
| **Atomic Science 1.2.0 (1.6.4)** | No matching upstream result in the reviewed data. The Voltz 3.0.0c binary and its pack libraries are available for an exact-input test. |
| **ICBM 1.4.1.344 (1.6.4)** | Upstream tested **1.4.2**, not the `.344` Voltz build. Some crafting and the assembler GUI worked after adding the IC2 dependency to their test set. This does not establish that the Voltz build or all gameplay works. |

**Checked locally with mcmodup:** both Voltz binaries reference the public instance method `universalelectricity/api/vector/Vector3#translate(Lnet/minecraftforge/common/ForgeDirection;)Luniversalelectricity/api/vector/Vector3;`. The pack's `universal-electricity-3.1.0.74-core.jar` declares that exact overload. This also makes UE 3.1.0.74 a candidate to test with Minechem, whose source calls the same overload. It verifies a **legacy signature**, not successful translation, a fix for the tick crash, or operation in a world.

To reproduce that lookup, extract just the UE JAR from the Voltz ZIP (keep it private), then run:

```sh
pack=.mcmodup/Voltz_Server_v3.0.0c.zip
ue=.mcmodup/universal-electricity-3.1.0.74-core.jar
python3 -m mcmodup scan-pack "$pack" --mod mods/atomicscience.jar \
  --source-version 1.6.4 --legacy-api "$ue" --json
```

Repeat with `--mod mods/icbm-1.4.1.344.jar`. In `api_checks`, the exact overload should have `legacy_lookup.status` set to `found`. A class listed under `binary_dependencies` alone does not establish this.

## Use the evidence to plan native parity

[`plan-port` and `decompile-mod`](mapped-decompilation.md#use-connector-evidence-without-importing-its-runtime) can import the raw CheckJar JSON for your selected mod. The checklist separates missing providers, unresolved or verifier findings, and **linked but empty** placeholders. Same-release client/server observations are separate, optional input. Neither the JSON nor Connector is needed to run a native port.

- **Thaumcraft 4.2.3.5:** use the upstream research, wand/aura, essentia and golem scenarios as regression references for the native source port, not as passes. Compare against the [native tests already in place](source-port-builds.md) and expand coverage of golem tasks, vis relay networks, mirrors and client overlays. Report visual and multiplayer deviations even when server tests pass.
- **Minechem 5.0.5 FINAL:** first reproduce a complete powered recipe in the original with the exact UE library. The [native preview](usage-examples.md) already exercises recovered machine recipes, journal discovery and recipe selection. Reactor, fluid and radiation behavior, original microscope assets and omitted recipes still need native implementation and comparison. Do not count screenshots without a confirmed binary and matching dependencies as baseline passes for this JAR.
- **Voltz 3.0.0c:** resolve Calclavia Core and UE first, and review the synthesized classes/members from the exact CheckJar set. Account for both the base ICBM and its dependent components. This is still an investigation: the closest ICBM source snapshot differs from `.344`, and exact authorized Atomic Science source has not been established. No native gameplay pass was measured for this set.

## Next runtime checks

The most useful next test for Minechem is to tick the original powered decomposer and synthesizer with UE 3.1.0.74, then verify a completed recipe. For the Voltz set, test server startup and block entities with the pinned Calclavia Core and UE libraries before attempting broader gameplay. Keep sets small; optional integrations should not be added just to make references disappear. For other inputs, the [upstream offline checker](https://github.com/kylepitcock/Legacy-Connector/blob/062c6e8d902083abae3adae2e856a657c7560ebb/docs/wiki/Development.md) accepts a mod and its dependencies as a set: `./gradlew checkLegacyMod -PlegacyJar=/absolute/mod.jar,/absolute/library.jar`.

For gameplay checks, make a disposable NeoForge instance. Put the [released Connector JAR](https://github.com/kylepitcock/Legacy-Connector/releases/tag/v0.5.0) in `mods/` and the original mods and libraries in `mods/1.7.10/`, `mods/1.6.4/`, or `mods/1.5.2/` as appropriate. Do not mix the native partial JARs into this test. Read `logs/legacyconnector-report.txt`, exercise the original features, and test save/reload in a **new** world before considering any legacy save.

For **playing original mods**, try Connector before rewriting each feature. For **producing a native JAR without Connector**, use its mappings, API implementations, and gameplay tests as references, not its cache as output. The mcmodup converter neither invokes nor bundles Connector.
