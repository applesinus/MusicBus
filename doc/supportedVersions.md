## Fabric Mod

| №   | Minecraft | Fabric Loader | Profile                        | Expected result                                                                                 | Got | Notes     |
| --- | --------- | ------------- | ------------------------------ | ----------------------------------------------------------------------------------------------- | --- | --------- |
| 1   | `26.3`    | `0.20.1`      | `fabric-loader-0.20.1-26.3`    | ✅ Launch, `getOrResolve`, `ValueUpdateListener`, unobf `MOJANG`                                 | ✅   |           |
| 2   | `26.2`    | `0.19.0`      | `fabric-loader-0.19.0-26.2`    | ✅ Launch, `ValueUpdateListener`, `resolve`, unobf `MOJANG`                                      | ✅   |           |
| 3   | `26.1.2`  | `0.18.0`      | `fabric-loader-0.18.0-26.1.2`  | ✅ Launch, `Consumer<Boolean>`, `PlayResult`, unobf `MOJANG`                                     | ✅   |           |
| 4   | `1.21.11` | `0.17.3`      | `fabric-loader-0.17.3-1.21.11` | ✅ Launch, `Intermediary*Mixin`, `getIdentifier`, `refreshCategoryVolume`                        | ✅   |           |
| 5   | `1.21.10` | `0.17.0`      | `fabric-loader-0.17.0-1.21.10` | ✅ Launch, `updateCategoryVolume(SoundSource)` (1 arg), `getLocation`                            | ✅   |           |
| 6   | `1.21.6`  | `0.16.14`     | `fabric-loader-0.16.14-1.21.6` | ✅ Launch, `PlayResult`, `pauseAllExcept`, `updateCategoryVolume(SoundSource, float)`            | ✅   |           |
| 7   | `1.21.4`  | `0.16.9`      | `fabric-loader-0.16.9-1.21.4`  | ✅ Launch, `play` → `void`, `pause()`, `SoundOptionsScreen.addOptions()`                         | ✅   |           |
| 8   | `1.20.4`  | `0.16.0`      | `fabric-loader-0.16.0-1.20.4`  | ✅ Launch, `SoundOptionsScreen.init()`, `addSmall(OptionInstance[])`                             | ✅   |           |
| 9   | `1.19.4`  | `0.15.11`     | `fabric-loader-0.15.11-1.19.4` | ✅ Launch, `INTENTIONALLY_EMPTY_SOUND` присутствует                                              | ✅   |           |
| 10  | `1.19.2`  | `0.15.5`      | `fabric-loader-0.15.5-1.19.2`  | ✅ Launch, `HAS_INTENTIONALLY_EMPTY_SOUND = false`, `OptionInstance`                             | ✅   |           |
| 11  | `1.18.2`  | `0.15.0`      | `fabric-loader-0.15.0-1.18.2`  | ✅ Launch, no button `OptionInstance`, reading via `Resource` + `calculateVolume(SoundInstance)` | ✅   | No button |
| 12  | `1.18.2`  | `0.14.25`     | `fabric-loader-0.14.25-1.18.2` | ❌ Negative test: failure due to `fabricloader >= 0.15.0`                                        | ❌   |           |
| 13  | `1.17.1`  | `0.15.0`      | `fabric-loader-0.15.0-1.17.1`  | ❌ Negative test: failure due to `minecraft >= 1.18` / `java >= 17`                              | ❌   |           |

---

## Standalone Java Agent (`-javaagent`)

| №   | Minecraft | Namespace        | Expected result                                                                                           | Got | Notes |
| --- | --------- | ---------------- | --------------------------------------------------------------------------------------------------------- | --- | ----- |
| 1   | `26.3`    | `MOJANG` (unobf) | ✅ Launch, `getOrResolve`, `ValueUpdateListener`, `detectChannelLambdas`                                   | ⏳   |       |
| 2   | `26.2`    | `MOJANG` (unobf) | ✅ Launch, `ValueUpdateListener` (`26.2+`)                                                                 | ⏳   |       |
| 3   | `26.1.2`  | `MOJANG` (unobf) | ✅ Launch, basic target, `Consumer<Boolean>`                                                               | ⏳   |       |
| 4   | `1.21.11` | `OBF`            | ✅ Launch, `getIdentifier`, `refreshCategoryVolume`                                                        | ⏳   |       |
| 5   | `1.21.10` | `OBF`            | ✅ Launch, `updateCategoryVolume(SoundSource)` (1 arg), `getLocation`                                      | ⏳   |       |
| 6   | `1.21.6`  | `OBF`            | ✅ Launch, `PlayResult`, `pauseAllExcept`, `updateCategoryVolume(SoundSource, float)`                      | ⏳   |       |
| 7   | `1.21.4`  | `OBF`            | ✅ Launch, `play` → `void`, `pause()`, `addOptions()`                                                      | ⏳   |       |
| 8   | `1.20.4`  | `OBF`            | ✅ Launch, `SoundOptionsScreen.init()`, `detectChannelLambdas`  `Channel`                                  | ⏳   |       |
| 9   | `1.19.4`  | `OBF`            | ✅ Launch, `INTENTIONALLY_EMPTY_SOUND`                                                                     | ⏳   |       |
| 10  | `1.19.2`  | `OBF`            | ✅ Launch, `HAS_INTENTIONALLY_EMPTY_SOUND = false`, `ResourceProvider.open`                                | ⏳   |       |
| 11  | `1.18.2`  | `OBF`            | ✅ Launch (Java 17): no button `OptionInstance`, reading via `Resource` + `calculateVolume(SoundInstance)` | ⏳   |       |
| 12  | `1.17.1`  | `OBF`            | ❌ Negative test (on Java 16: `UnsupportedClassVersionError` due to target Java 17)                        | ⏳   |       |