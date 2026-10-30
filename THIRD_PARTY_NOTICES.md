# 移植来源与研究参考

本 Android 工程是对 [yekong-nasa/Z30-Recipes](https://github.com/yekong-nasa/Z30-Recipes)
（MIT License, Copyright (c) 2026 Z30 Recipes contributors）的完整移植：将 iOS 版
（Swift/SwiftUI）的 NCP/NP3 文件编解码、PTP/IP 相机会话与配方传输逻辑重写为
Kotlin/Jetpack Compose。原版权声明已保留在 LICENSE 中。

## 研究参考（来自上游 THIRD_PARTY_NOTICES.md，原样延续）

本仓库自行实现了文件编解码与 PTP/IP 会话，不捆绑、不重新分发下列项目或其配方集。
上游在调查文件布局与协议行为时参考过以下资料：

- [ssssota/nikon-flexible-color-picture-control](https://github.com/ssssota/nikon-flexible-color-picture-control), MIT. Flexible Color 字段、曲线布局与 NP3 序列化研究。
- [horshack-dpreview/NikonPictureControlsDev](https://github.com/horshack-dpreview/NikonPictureControlsDev). Picture Control 曲线与查找表研究。
- [tvgenious89/nikon-pc](https://github.com/tvgenious89/nikon-pc). 传统 Picture Control 编辑器与文件格式对比。其曲线实现未复制到本工程。
- [fistulareffigy/Nikon-NP3-NCP-TOOL](https://github.com/fistulareffigy/Nikon-NP3-NCP-TOOL), MIT. 基于模板的修复研究；其修复实现未使用。
- [dukus/digiCamControl](https://github.com/dukus/digiCamControl) 与 [discordj/QNikonCamera](https://github.com/discordj/QNikonCamera). 尼康操作标识符与历史数据处理参考；未捆绑其源码实现。
- [Nikon Z30 online manual](https://onlinemanual.nikonimglib.com/z30/en/09-03-12.html) 与 [NX Studio Flexible Color documentation](https://nikonimglib.com/nxstdo/onlinehelp/en/the_picture_controls_flexible_color_23.html)。

Nikon 及其产品名称归各自所有者。本工程 MIT 许可仅适用于本工程代码与生成的测试夹具，
不适用于外部软件、尼康文档或第三方摄影预设。

## 移植边界

- 本工程**不捆绑、不重新分发任何第三方作者的摄影配方（NCP/NP3）**；测试夹具仅为本工程
  自造的格式验证数据，不来自任何第三方配方集。
- 本工程为独立第三方移植，与尼康株式会社无关联、未获其背书；宣传与说明中不得暗示
  官方授权。
