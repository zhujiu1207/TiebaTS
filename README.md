# TiebaTS（贴吧TS）

[![Telegram](https://img.shields.io/badge/Telegram-中转群-26A5E4?logo=telegram&logoColor=white)](https://t.me/TabSwitch)

> **项目延续说明**：本仓库是 [GuhDoy/TiebaTS](https://github.com/GuhDoy/TiebaTS)（已归档，2024-08 停更）的后续开发版本，原项目的功能在此基础之上继续维护与演进。感谢原作者与社区贡献者。

一个基于 [libxposed API 102](https://github.com/libxposed/api) 的百度贴吧增强模块，注入宿主 `com.baidu.tieba`。

- 当前适配宿主版本：**22.14.1.0**（`app/build.gradle.kts` 中 `BuildConfig.TARGET_VERSION`）
- 最低支持宿主版本：12.53.1.0（`BuildConfig.MIN_VERSION`）
- 系统要求：Android 9+（minSdk 28），**arm64-v8a** 设备
- 运行框架：LSPosed（libxposed 新 API）或 LSPatch

> **⚠️ 已知问题**：Android 16+ 设备上可能出现宿主闪退，具体原因暂不清楚（可能与新系统行为变化或 native 兼容性有关），目前正在排查。如果你在这类设备上遇到闪退，欢迎提供日志（模块设置里开启「运行日志」后导出）到 [Issues](../../issues)，或直接提交修复 PR——PR 建到本仓库即可。

## 功能一览

模块所有功能均可在贴吧 App 内的模块设置页独立开关。

**净化**
- 强力去广告：信息流广告卡片、帖子页列表广告、吧页推广、游戏悬浮条、搜索框推广词、Pb 渲染前插入广告、首楼推荐、掉落红包、群聊横幅、视频流广告等 10+ 个广告源（网络层 + UI 层双拦截）
- 界面清理：首页顶栏/顶部 Tab/搜索图标、消息中心、我的页面、吧页 Tab、签到弹窗、云对话框、转发群聊入口、更新提示、评论工具栏等
- 内容过滤：首页/吧页/热门/直播/赏金/游戏推广/商品/评分/推荐吧等内容流过滤，关键词与用户过滤
- 低配模式：降低宿主渲染负载

**增强**
- 楼中楼最新排序（Vue2 宿主走本地排序；22.14+ Vue3 宿主走 TbInfra 事件总线桥接，见 `assets/SubPbLatestSort.js`）
- 一键抽奖：聊天群抽奖四步台账流程，含毒消息防护、转换链自学习、历史拉取与跨午夜日台账
- 自动签到 / 自动打开签到
- 保存图片、选择剪贴板、历史缓存、查看器原图、用户主页 ID 面板等

## 工作原理

- **Java/Kotlin 层**：经 `bridge/` 兼容层把 libxposed API 102 的生命周期翻译为传统 hook 入口；约 130 个按名钩点 + 形状定位（参数/返回类型匹配）兜底混淆名漂移。
- **反混淆规则库**：基于 [DexKit](https://github.com/LuckyPray/DexKit) 在宿主 dex 内按形状检索混淆类/方法。宿主 dex 变更（升级）后由设备端经 dex SHA-1 签名检测自动重建规则库，首次启动新宿主版本时会请求确认后重建并重启。
- **Native 层**（`app/src/main/cpp/`）：
  - `check/` — Xposed/hook 框架检测对抗（源自 Brevent 检测库，见下方"致谢"）
  - `hide/` — 模块 so 库隐藏
  - `crash/` — native 崩溃捕获
- **JS 注入**（`app/src/main/assets/`）：H5 页净化与楼中楼排序脚本，注入宿主 WebView。

## 构建

依赖：JDK 17、Android SDK（platform `android-37.0`）、NDK 27、CMake 3.18+。

```powershell
gradlew.bat compileDebugKotlin --console=plain             # 快速验证 Kotlin 编译
gradlew.bat :app:testDebugUnitTest --console=plain         # JVM 单测
gradlew.bat :app:externalNativeBuildDebug --console=plain  # 原生编译
gradlew.bat assembleRelease --console=plain                # 完整发版构建（含 R8/lint）
```

签名配置（可选）：项目根目录放 `keystore.properties`（`storeFile` / `storePassword` / `keyAlias` / `keyPassword` 四项），或用环境变量 `TS_KEYSTORE` / `TS_STORE_PASS` / `TS_KEY_ALIAS` / `TS_KEY_PASS`；两者都没有时构建出未签名包，可用 apksigner 手动签名。密钥文件不进版本库（见 `.gitignore`）。

## 安装

1. 从 [Releases](../../releases) 下载 `TS_x.y.z.apk`（或按上文自行构建）；
2. **LSPosed**：在 LSPosed 管理器中安装并启用本模块，作用域勾选「百度贴吧」，重启贴吧；
3. **LSPatch**：用 LSPatch 以模块模式重打包贴吧 APK（sigBypassLevel 2）。

首次在某个宿主版本上启动时，模块会构建反混淆规则库（需要一点时间），完成后确认重启即可。

## 致谢

- [Brevent](https://github.com/brevent/brevent)（liudongmiao）— `app/src/main/cpp/check/` 下的 Xposed 检测对抗代码（`anti-xposed.*`、`art.h`、`hash.*`、`inline.*`、`plt.*` 等）衍生自其 `brevent/native/jni/` 检测库，头文件守卫 `BREVENT_*` 保留以示可溯源。原代码以 WTFPL 发布，本模块按 GPL-3.0 整体分发。
- [libxposed API](https://github.com/libxposed/api) / [LSPosed](https://github.com/LSPosed/LSPosed) / [LSPatch](https://github.com/LSPosed/LSPatch)
- [DexKit](https://github.com/LuckyPray/DexKit)

## 许可证

本项目以 [GPL-3.0](LICENSE) 分发。

## 免责声明

- 本项目仅供学习与技术交流使用，请于下载后 24 小时内自行评估是否继续使用；
- 使用本模块修改百度贴吧客户端行为可能违反其用户协议，由此产生的账号受限等风险由使用者自行承担；
- 本项目与百度官方没有任何关联，也不接受任何形式的商业使用；
- 请在遵守当地法律法规的前提下使用。
