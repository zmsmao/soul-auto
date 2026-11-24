# 参与贡献

感谢你关注 SoulBot。欢迎通过 Issue 反馈问题，也欢迎提交 Pull Request 改进项目。

## 提交问题

提交 Bug 时，请尽量说明：

- Android 版本和设备型号。
- Soul 客户端版本。
- 复现步骤和预期结果。
- 实际结果，以及相关的脱敏日志或截图。

提交日志和截图前，请删除聊天对象信息、账号标识、API Key、访问令牌及其他个人信息。不要上传真实聊天导出文件。

## 提交 Pull Request

1. 从最新的 `master` 创建独立分支。
2. 每个 PR 尽量只解决一个问题。
3. 在描述中说明修改目的、主要变化和可能影响。
4. 为修复或新增的逻辑补充适当测试。
5. 确认没有提交密钥、个人数据、构建产物或无关文件。

建议使用以下分支前缀：`feat/`、`fix/`、`refactor/`、`docs/`。

## 开发环境

- JDK 17
- Android SDK
- Android Studio（可选，也可以使用 Gradle 命令行）

项目 Android 工程位于 `android-app/`。

在该目录运行基础检查：

```bash
./gradlew testDebugUnitTest assembleDebug lintDebug
```

Windows PowerShell：

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug lintDebug
```

## 代码与文档

- 保持页面操作、自动化流程、模型调用和数据存储的职责清晰。
- 避免提交真实用户聊天内容和身份信息。
- 用户可见功能、安装步骤或配置项发生变化时，同步更新 README 或使用指南。
- 请勿将尚未实现或未经验证的功能描述为已完成。

## 发布

正式版本由项目维护者通过 GitHub Actions 发布。请勿在 PR 中提交签名密钥、密钥密码或个人签名文件。
