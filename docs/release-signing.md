# SoulBot 正式签名与自动发版

本文说明如何为 SoulBot 创建并保管唯一的 Android 发布签名密钥，以及如何让 GitHub Actions 自动构建、验证并发布正式 APK。

## 当前状态

- Debug CI 继续执行单元测试、Debug APK 构建和 Lint，供日常验证使用。
- 正式发版由 `.github/workflows/android-release.yml` 负责，只响应推送到仓库的 `v*` Tag。
- Release workflow 会检查 Tag 是否已经位于 `master` 历史中，并要求 Tag 与 Gradle 的 `versionName` 完全一致。
- Release APK 使用固定签名密钥签名，签名验证通过后才会创建 GitHub Release 并上传 APK 与 SHA-256 文件。
- 仓库不保存签名密钥或密码。首次使用前，仓库所有者必须自行创建密钥并配置 GitHub Actions Secrets。

## 1. 创建唯一签名密钥

在自己的电脑上安装 JDK 17，并在一个安全目录中执行：

```bash
keytool -genkeypair -v \
  -keystore soulbot-release.jks \
  -storetype JKS \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000 \
  -alias soulbot
```

按照提示设置 Keystore 密码、Key 密码和证书信息。请记下：

| 项目 | 用途 |
|---|---|
| Keystore 文件 | `soulbot-release.jks` |
| Store password | Keystore 密码 |
| Key alias | 固定为 `soulbot` |
| Key password | `soulbot` 对应的私钥密码 |

建议 Store password 与 Key password 使用不同的强密码。不要把密码写入脚本、Gradle 文件或 Git 仓库。

查看并离线保存证书 SHA-256 指纹：

```bash
keytool -list -v -keystore soulbot-release.jks -alias soulbot
```

**非常重要：** 这把密钥是 SoulBot 后续所有正式版本的身份凭据。请至少保留两份加密离线备份，并分别保管密码。不要把 JKS 文件上传到 GitHub、网盘公开目录或聊天窗口。丢失密钥后，无法使用新密钥直接覆盖安装旧签名版本。

## 2. 配置 GitHub Actions Secrets

先将 JKS 文件编码为单行 Base64 字符串。Base64 只是编码，不是加密；不要把编码结果提交到仓库。

Windows PowerShell：

```powershell
$bytes = [System.IO.File]::ReadAllBytes("$PWD\soulbot-release.jks")
[Convert]::ToBase64String($bytes) | Set-Clipboard
```

macOS / Linux：

```bash
base64 < soulbot-release.jks | tr -d '\n' > soulbot-release.jks.base64
```

打开仓库：

**Settings → Secrets and variables → Actions → New repository secret**

依次创建以下四个 Repository secrets：

| Secret 名称 | Secret 值 |
|---|---|
| `SOULBOT_SIGNING_KEYSTORE_BASE64` | JKS 文件的单行 Base64 内容 |
| `SOULBOT_SIGNING_STORE_PASSWORD` | 创建 Keystore 时设置的 Store password |
| `SOULBOT_SIGNING_KEY_ALIAS` | `soulbot` |
| `SOULBOT_SIGNING_KEY_PASSWORD` | 创建密钥时设置的 Key password |

只需配置一次。不要在聊天中发送这些 Secret，也不要把密钥放入 GitHub Actions 普通变量（Variables）。

## 3. 发布新版本

正式发布前：

1. 在功能分支完成开发并通过 Pull Request 合入 `master`。
2. 更新 `android-app/app/build.gradle` 中的 `versionName` 和 `versionCode`。每次对外发布必须提高 `versionCode`；版本名称使用完整语义化版本，例如 `2.1.0`。
3. 同步更新 `CHANGELOG.md` 和相关文档。
4. 确认当前 `master` 最新提交已经通过 Debug CI，并完成必要的真机验收。
5. 确认四个 GitHub Secrets 已配置，再在本地 `master` 上创建与 `versionName` 一致的 Tag。

例如，当前项目配置为 `versionName "2.0.0"` 时：

```bash
git switch master
git pull --ff-only
git tag -a v2.0.0 -m "SoulBot 2.0.0"
git push origin v2.0.0
```

Tag 推送后，GitHub Actions 会自动：

- 确认 Tag 指向已经合入 `master` 的提交；
- 检查 Tag 与 Android `versionName` 一致；
- 执行 Debug / Release JVM 单元测试；
- 构建正式 Release APK 并运行 Android Lint；
- 使用固定 JKS 签名，并通过 Android `apksigner` 验证签名；
- 计算 APK 的 SHA-256；
- 创建 GitHub Release，上传 `SoulBot-版本号.apk` 和对应的 `.sha256` 文件。

任一验证失败都会终止发版，不会继续上传安装包。若 Secrets 缺失，工作流会明确失败并提示补充配置，不会回退成 Debug 签名。

## 4. 本地构建正式 Release APK（可选）

本地构建时也使用同一套环境变量。不要将真实密码写入仓库文件或提交到版本控制。

macOS / Linux 示例：

```bash
export SOULBOT_SIGNING_STORE_FILE="$HOME/keys/soulbot-release.jks"
read -s -p "Keystore password: " SOULBOT_SIGNING_STORE_PASSWORD; export SOULBOT_SIGNING_STORE_PASSWORD; echo
export SOULBOT_SIGNING_KEY_ALIAS="soulbot"
read -s -p "Key password: " SOULBOT_SIGNING_KEY_PASSWORD; export SOULBOT_SIGNING_KEY_PASSWORD; echo

cd android-app
./gradlew testReleaseUnitTest assembleRelease lintRelease
```

Release APK 输出路径：

`android-app/app/build/outputs/apk/release/app-release.apk`

没有完整配置四个环境变量时，Gradle 会拒绝执行 Release APK 打包任务，避免无意生成未签名安装包。

## 5. 从 Debug 版迁移到正式签名版

目前已经提供过的 SoulBot 2.0 Debug APK 使用 Android Debug 签名，不是本流程创建的正式签名。Android 不允许用不同签名证书的 APK 直接覆盖安装。

因此，从旧 Debug 版切换到第一次正式签名版时，需要先备份应用内的重要数据，再卸载 Debug 版并安装正式签名版。卸载可能清除仅保存在应用本地的数据，请先确认备份可用。

完成这次迁移后，只要以后始终使用同一份 JKS、相同的 applicationId（`com.soulbot.app`），且每次递增 `versionCode`，后续正式版本即可直接覆盖更新，无需重复卸载。

## 6. 密钥轮换与事故处理

- 不要为了方便在不同机器或不同 CI 中重新生成密钥。
- 如果怀疑 JKS 或密码泄露，应先停止发布，评估已发布 APK 的签名影响，再制定迁移方案。
- 不能把“重新生成一把 JKS”当作普通密码重置；新证书与旧证书不同，用户安装更新会受到影响。
- 证书指纹应与最初生成时保存的离线记录核对。发布后也可使用 `apksigner verify --print-certs` 检查 APK 证书。
