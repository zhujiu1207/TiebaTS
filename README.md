# TiebaTS（贴吧TS）

[![Telegram](https://img.shields.io/badge/Telegram-群组-26A5E4?logo=telegram&logoColor=white)](https://t.me/+hA4cxr3IA6NhYWI1)

> **项目延续说明**：本仓库是 [GuhDoy/TiebaTS](https://github.com/GuhDoy/TiebaTS)（已归档，2024-08 停更）的后续开发版本，原项目的功能在此基础之上继续维护与演进。感谢原作者与社区贡献者。

一个基于 [libxposed API 102](https://github.com/libxposed/api) 的百度贴吧增强模块，注入宿主 `com.baidu.tieba`。

- 当前适配宿主版本：**22.15.1.1**（`app/build.gradle.kts` 中 `BuildConfig.TARGET_VERSION`）
- 最低支持宿主版本：12.53.1.0（`BuildConfig.MIN_VERSION`）

> **⚠️ 已知问题**：Android 16+ 设备上可能出现宿主闪退，具体原因暂不清楚（可能与新系统行为变化或 native 兼容性有关），目前正在排查。如果你在这类设备上遇到闪退，欢迎提供日志（模块设置里开启「运行日志」后导出）到 [Issues](../../issues)，或直接提交修复 PR——PR 建到本仓库即可。

**功能**

去广告 - 界面精简 - 内容过滤 - 楼中楼排序更改 - 自动签到 / 自动打开签到等

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
