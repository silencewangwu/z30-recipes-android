# Z30 Recipes · Android  AI移植版

尼康 Z30 无线 Picture Control 配方管理工具 —— 由 iOS 版
[yekong-nasa/Z30-Recipes](https://github.com/yekong-nasa/Z30-Recipes) 移植为
Android（Kotlin + Jetpack Compose）。MIT 许可，原版权声明见 LICENSE。

## 功能

- **NCP / NP3 编解码**：读取尼康 Z30 导出的传统 Picture Control 文件（NCP、NP3），
  解析曲线、参数与 LUT，并按相机 PTP wire 布局重新编码。
- **Wi-Fi 直连相机**：PTP/IP 会话（端口 15740），自动发现、握手、心跳保活，
  兼容 SnapBridge 关闭后的直连场景。
- **配方库**：批量导入、按内容去重、命名排序、删除。
- **载入相机**：替换任意 C1–C9 槽位并应用；批量配置 C1–C9，逐个槽位回读校验；
  写入前生成恢复记录，中断后可一键恢复，相机不会被留在半写入状态。
- **诊断日志**：会话与传输日志落盘（`filesDir/LatestDiagnostics.txt`），
  传输结果落盘（`filesDir/LatestTransfer.json`）。

## 构建

```bash
# 环境要求：JDK 17+、Android SDK 34（build-tools 34.0.0）
export ANDROID_HOME=/path/to/sdk
./gradlew :core:test        # 编解码单元测试（25 个，复刻自 iOS 测试集）
./gradlew :app:assembleDebug  # 构建 Debug APK
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`。

## 架构

```
:core —— 纯 Kotlin/JVM，零 Android 依赖
  ├─ PTPCodec：Np3Document / ClassicNcpRecipe / StandardNp3Recipe /
  │            NeutralNp3Recipe / OrdinaryNp3Recipe / PhoneRecipeDocument /
  │            C9RecipePayload / NeutralPictureControlPayload / BinaryReader
  ├─ PTPIPProbe：PacketSocket / PtpIpSession / 心跳 / 事务 / 恢复
  └─ RecipeLibrary：导入器 / 配方列表 / 恢复记录存储

:app —— Android UI（Jetpack Compose, Material3 深色主题）
  └─ RecipeLibraryViewModel（对应 iOS RecipeLibraryModel）
       RecipesApp / BatchTransferScreen
```

> 注意：UI 层为移植自 iOS SwiftUI 的界面；协议与会话逻辑为逐行移植的 Kotlin 版本，
> 已通过复刻自 iOS 的 25 项单元测试验证。PTP/IP 会话在 Android 上保持前台运行；
> 后台保活（前台服务）未包含在本版本中。

## 合规

- 本工程不捆绑、不重新分发第三方作者的摄影配方；测试夹具为本工程自造格式验证数据。
- 与尼康株式会社无关联、未获背书；不得暗示官方授权。
- 详细来源与研究参考见 THIRD_PARTY_NOTICES.md。
