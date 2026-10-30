# PORTING —— 增量移植同步记录

本工程是 [yekong-nasa/Z30-Recipes](https://github.com/yekong-nasa/Z30-Recipes)
（Swift/SwiftUI，MIT）的 Android 移植版（Kotlin/Jetpack Compose）。
Swift 仓库是**唯一权威上游**；每次上游更新按本文件流程增量同步。

## 文件映射表

| 上游文件 | Android 落点 | 翻译强度 |
|---|---|---|
| `Sources/Core/PTPCodec.swift` | `core/src/main/kotlin/com/z30lab/core/ByteUtils.kt`、`Recipes.kt`、`ClassicNcpRecipe.kt` | 逐行对应 |
| `Sources/Core/PTPIPProbe.swift` | `core/…/PacketSocket.kt`、`PtpIpSession.kt` | 逐行对应 |
| `Sources/App/Z30LabApp.swift` | `app/…/RecipeLibraryViewModel.kt` + `RecipesApp.kt`、`BatchTransferScreen.kt` | 行为对齐，UI 按 Compose 重写 |
| `Tests/CoreTests/*.swift` | `core/src/test/kotlin/…/CodecTests.kt` + `core/src/test/resources/` | 逐用例翻译，夹具二进制一致 |
| `docs/PROTOCOL.md` | 阅读吸收，更新代码注释与操作码表 | — |

## 同步流程（每次上游更新执行一遍）

1. **检测**：`git fetch upstream`，或关注仓库 Watch / release。
2. **定位**：`git log upstream/main --oneline` + `tools/sync-check.sh`（自动列出改动文件 → 落点）。
3. **翻译**：按映射表分层落 Kotlin，回放踩坑清单（见下）。
4. **测试契约**：把上游新增/修改的测试译成 Kotlin、夹具拷入 `test/resources/`，跑 `:core:test` 必须全绿。
5. **回归**：`:app:assembleDebug` 出 APK，真机回归 C 槽读写；更新 PORTING.md 同步记录。

## 踩坑清单（每次翻译逐项自查）

- [ ] `Byte.toInt()` 是有符号扩展；Swift `UInt8 → Int` 无符号 → 0..255 参与比较/算术处用 `Byte.u()`（`and 0xFF`）。保留两处 Int8 语义：参数四分位值、单色 filter/toning 的 ±128。
- [ ] Swift Dictionary/Data 是值拷贝；Kotlin `HashMap` 浅拷贝共享 `ByteArray` → 修改夹具/记录前 `clone()`。
- [ ] NP3 容器 tag/len 为大端（`putBig32`）；wire 载荷内字段按各自字节序。
- [ ] 握手载荷：GUID 16 字节 + `"Z30 Recipes\0"` UTF-16LE + `0x00010000`（小端）。
- [ ] 新增 PTP 操作码时：更新 `info.operations` 前置检查、`PtpIpSession.operation()` 白名单语义、代码注释操作码表。
- [ ] `Np3Document` 记录数/长度/重复校验与 Swift `guard` 逐一对应。
- [ ] 传输前"恢复记录"（Selection/Recipe Recovery）的写入前保存逻辑不可省略。

## 同步记录

| 日期 | 上游 commit | 变更范围 | 涉及 Android 文件 | core 测试 | 真机回归 | 备注 |
|---|---|---|---|---|---|---|
| 2026-10-30 | `3eeb22a`（初始移植基线） | 全量移植 | 全部 core + app | 25/25 通过 | 批量配置已用（"当前状态不同"为正常展开行为，见 RecipesApp ReportCard 文案） | 初始提交 `382585f` |
