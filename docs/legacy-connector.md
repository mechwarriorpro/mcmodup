# What Legacy Connector can do for these ports

[Legacy Connector 0.5.0](https://github.com/kylepitcock/Legacy-Connector/releases/tag/v0.5.0) is a runtime translator for **NeoForge 1.21.1**, not a standalone JAR exporter. It covers our three input versions: 1.6.4 and 1.7.10 are beta; 1.5.2 is alpha. This review uses [the 0.5.0 source](https://github.com/kylepitcock/Legacy-Connector/tree/062c6e8d902083abae3adae2e856a657c7560ebb), built against NeoForge `21.1.258`; compatibility with mcmodup's `21.1.267` is not established by this review.

On launch, Connector translates original mod bytecode against its legacy API implementation, then connects the old behavior to the modern game. The 1.6.4 and 1.5.2 layers reuse the 1.7.10 layer. It also handles some library substitutions, such as Baubles → Curios when Curios is installed. An unimplemented API can be replaced by a placeholder, so translating, linking, starting a world, and playing a feature are separate tests.

The translated cache **needs Connector**. [Its loader rejects cached derivatives placed directly in `mods/`](https://github.com/kylepitcock/Legacy-Connector/blob/062c6e8d902083abae3adae2e856a657c7560ebb/docs/wiki/Cache-and-Redistribution.md). Neither the cache nor the original files should be committed or redistributed without rights. Connector's [MIT license](https://github.com/kylepitcock/Legacy-Connector/blob/062c6e8d902083abae3adae2e856a657c7560ebb/LICENSE) covers its own code, not the legacy mods.

## Evidence for the example mods

These are **upstream results**, not mcmodup runtime tests. See the [pinned compatibility entries](https://github.com/kylepitcock/Legacy-Connector/blob/062c6e8d902083abae3adae2e856a657c7560ebb/docs/data/mods.json) and [gameplay test log](https://github.com/kylepitcock/Legacy-Connector/blob/062c6e8d902083abae3adae2e856a657c7560ebb/docs/wiki/Gameplay-Test-Log.md#thaumcraft-4235-headless-server-audit).

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

## Recommended experiment

1. Start with the exact Thaumcraft 4.2.3.5 release and its libraries. Next, test Minechem with the UE 3.1 candidate and test the two Voltz mods with their original Calclavia Core/UE libraries. Keep each test set small; do not treat a missing optional integration as a required dependency or add conflicting versions just to make a link pass.
2. In a separate Connector source checkout at `v0.5.0`, with JDK 21 and Gradle network access, run the upstream [offline checker](https://github.com/kylepitcock/Legacy-Connector/blob/062c6e8d902083abae3adae2e856a657c7560ebb/docs/wiki/Development.md) on each mod and its dependencies **as a set**: `./gradlew checkLegacyMod -PlegacyJar=/absolute/mod.jar,/absolute/library.jar`. Review missing dependencies, unresolved references, and synthesized APIs. This does not start Minecraft.
3. For gameplay checks, make a disposable NeoForge 1.21.1 instance. Put the [released Connector JAR](https://github.com/kylepitcock/Legacy-Connector/releases/tag/v0.5.0) in `mods/` and the original mods and libraries in `mods/1.7.10/`, `mods/1.6.4/`, or `mods/1.5.2/` as appropriate. Do not mix the native partial JARs into this test. Read `logs/legacyconnector-report.txt`, exercise the original features, and test save/reload in a **new** world before considering any legacy save.

For **playing original mods**, try Connector before rewriting each feature. For **producing a native JAR without Connector**, use its mappings, API implementations, and gameplay tests as references, not its cache as output. mcmodup does not yet invoke Connector or bundle it, and this review did not run its offline translator or its game runtime.
