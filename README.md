# SoulBot 自动助手 2.0

SoulBot 是一个运行在 Android 手机上的 Soul 自动化辅助工具。它通过 Android 无障碍服务识别 Soul 页面、读取系统可访问的聊天内容并执行页面操作；通过兼容 OpenAI Chat Completions 的模型接口生成回复，并用悬浮控制器展示任务状态、下一步动作和倒计时。

**当前版本：2.0** · Android 8.0（API 26）及以上 · Kotlin / Android Views / Material Components

## 2.0 版本重点

- **更完整的对话理解**：综合近期消息、承接问题、上下文锚点和会话状态，识别“都没有”“两者都不是”等依赖前文的短答，也能接住“你发”“那你说”“然后呢”等话题交接。
- **按场景选择生成深度**：普通消息走轻量生成；需要更多上下文的轮次进入审核；明显情绪或关系信号进入分析与审核流程，避免每条消息都付出多轮模型调用。
- **节奏控制**：连续低信息回应且没有待答问题或重要信号时，允许本轮等待；不会因为第一条短回复、明确问题、重要情绪或自然结束信号就机械暂停。
- **账号资料可控**：支持性别、年龄、地区、星座、身高、职业和补充资料，并为每项设置主动公开、被问时回答或不公开。
- **未读会话优先级**：从未读会话预览中识别联系方式、见面/照片和重要情绪跟进等信号；同优先级内仍结合消息时间排序。
- **更易维护的 Android 结构**：按自动化调度、无障碍操作、场景流程、会话、模型、策略、数据和诊断分层。
- **界面与品牌更新**：统一 Material 3 日夜间主题、设置页与导航视觉，并使用专属 SoulBot 启动图标。

## 自动化能力

- 两套互斥模式：**星球与聊天**、**广场与私聊**。
- 奇遇铃、新招呼和未读私聊优先于当前模式的空闲任务。
- 灵魂匹配、奇遇铃、广场和普通私聊分别执行对应场景流程。
- 多段上下文、个人资料、人工回复风格样本和本地会话状态辅助生成。
- 最多配置 5 个模型端点，支持优先级、故障切换、冷却与单端点测试。
- 未读语音可调用 Soul 客户端的“转文字”，等待结果稳定后再生成回复。
- 支持聊天回放盲选对比、人工改稿与本地学习反馈；回放候选不会自动发送到 Soul。
- 联系人聊天记录可查看、导出、合并导入与清理；另有证据关联的结构化联系人记忆，默认不自动提取。
- 提供设备适配诊断、脱敏运行日志、诊断导出和悬浮任务控制器。

## 项目结构

Android Kotlin 源码位于 `android-app/app/src/main/java/com/soulbot/app/`：

| 模块 | 职责 |
|---|---|
| `app` | Application、Activity、无障碍 Service 生命周期与组合入口 |
| `automation` | 自动化主循环、任务调度和 Host 接口 |
| `accessibility` | Soul 页面识别、无障碍节点操作、消息读写与导航 |
| `workflow` | privatechat、greeting、match、qiyu、square 场景流程 |
| `conversation` | 会话身份、上下文、状态与联系人记忆 |
| `model` | 模型客户端、端点配置、路由和回复生成引擎 |
| `policy` | 回复生成深度、回复节奏、开场和场景规则 |
| `data` | 本地数据库、设置与备份持久化 |
| `diagnostics` | 运行日志、设备兼容性诊断和聊天回放 |

新增功能应保持模块边界清晰：页面操作不承载聊天策略，场景流程不直接依赖所有 Android Service 实现，模型路由也不负责决定 Soul 页面操作。

## Releases

当前代码版本为 **2.0.0**（Android versionName `2.0`，versionCode `3`）。版本变化见 [CHANGELOG.md](CHANGELOG.md)。GitHub 正式安装包发布后会出现在 [Releases](https://github.com/zmsmao/soul-auto/releases) 页面；CI 中的 Debug APK 属于验证构建，不等同于正式签名版本。

## 文档

- [开发与维护约定](CONTRIBUTING.md)
- [Android 使用、设置与开发说明](android-app/README.md)
- [产品定义与设计原则](android-app/PRODUCT.md)
- [会话上下文与身份架构审计](docs/chat-context-baseline.md)
- [证据型联系人记忆 v1](docs/contact-memory-v1.md)
- [主界面视觉更新](docs/ui-refresh.md)
- [联系人记忆界面更新](docs/ui-apple-memory-refresh.md)

## 构建与测试

在 `android-app` 目录运行：

```bash
./gradlew testDebugUnitTest assembleDebug lintDebug
```

Windows PowerShell：

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug lintDebug
```

Debug APK 输出路径：`app/build/outputs/apk/debug/app-debug.apk`。

CI 使用 JDK 17 执行单元测试、Debug APK 构建和 Android Lint。2.0 合并前的验证提交为 `d23242e9485ef599a7b088d41424c8814a0e2f69`，对应 GitHub Actions #175 全部通过；合并提交为 `10fc6605dda113cb02ce359ab87425dddf25f5fd`。

## 使用边界

SoulBot 依赖 Soul 当前暴露给 Android 无障碍服务的页面结构和可见内容。Soul 更新、设备后台管理、无障碍节点差异或模型服务变动都可能影响运行。模型请求会把所选聊天片段和配置资料发送给对应服务商；聊天导出文件为明文数据。建议先完成设备诊断、开启悬浮控制器监督，并妥善保护 API 密钥与备份文件。
