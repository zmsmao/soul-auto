# SoulBot 自动助手

SoulBot 是运行在 Android 手机上的 Soul 聊天自动化助手。项目通过 Android 无障碍服务识别 Soul 页面、读取可见聊天内容并执行页面操作，通过 OpenAI 兼容接口生成回复，并使用悬浮控制器展示任务状态。

完整功能说明、使用方式、设置项、数据备份、诊断与构建步骤见：

- [Android 使用与开发说明](android-app/README.md)
- [产品定义](android-app/PRODUCT.md)

## 当前能力概览

- 星球与聊天、广场与私聊两种互斥自动化模式。
- 奇遇铃、新招呼与未读聊天优先于模式空闲任务。
- 多段聊天上下文、联系人记忆、人工回复风格学习和多模型故障切换。
- 灵魂匹配、奇遇铃、同城广场和普通私聊等分场景流程。
- 聊天回放盲选对比与人工修改反馈；回放只用于测试/学习，不会自动发送候选消息。
- 未读语音转文字、设备页面适配诊断、结构化运行日志。
- 本地聊天记忆查看、导入、导出与清理。
- 悬浮控制器显示运行状态、下一步动作和倒计时。

## 代码结构

Android Kotlin 源码按职责拆分在 com.soulbot.app 下：

| 目录 | 职责 |
|---|---|
| app | Application、Activity、设置界面和 Service 组合入口 |
| automation | 自动化主循环、任务调度 |
| accessibility | 无障碍操作、页面识别、消息读写和导航 |
| workflow | privatechat、greeting、match、qiyu、square 场景流程 |
| conversation | 会话状态、身份、上下文与记忆逻辑 |
| model | 模型客户端、端点、路由和生成引擎 |
| policy | 回复、开场、节奏和场景策略 |
| data | 本地数据库与设置持久化 |
| diagnostics | 运行日志、设备兼容性、诊断与回放 |

新增功能时请遵循 android-app/README.md 中的模块边界说明。目录拆分是代码组织方式，不代表聊天策略或数据库行为发生变化。

## 构建

在 android-app 目录执行：

```bash
./gradlew testDebugUnitTest assembleDebug lintDebug
```

Windows PowerShell：

```powershell
.\\gradlew.bat testDebugUnitTest assembleDebug lintDebug
```

Debug APK 输出位置：app/build/outputs/apk/debug/app-debug.apk。

## 说明

本项目面向个人设备上的自动化研究与辅助操作。无障碍服务只能读取 Soul 暴露给系统的可见内容；Soul 页面改版、设备后台限制和模型服务差异都可能影响运行。请在使用时保留人工监督，并妥善保护聊天导出文件与模型密钥。
