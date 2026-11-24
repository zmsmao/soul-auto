# SoulBot 正式签名与自动发版

本文说明如何为 SoulBot 创建并保管唯一的 Android 发布签名密钥，以及如何让 GitHub Actions 自动构建、验证并发布正式 APK。

## 当前状态

- Debug CI 继续执行单元测试、Debug APK 构建和 Lint，供日常验证使用。
- 正式发版由 `.github/workflows/android-release.yml` 负责，支持两种入口：GitHub 网页端手动运行 `Android Release` workflow，或推送 `v*` Tag。
- 网页端发版必须选择 `master`，输入的 Tag 必须与 Gradle 的 `versionName` 完全一致；workflow 会自动创建 Tag 并发布 Release。
- Release APK 使用固定签名密钥签名，签名验证通过后才会创建 GitHub Release 并上传 APK 与 SHA-256 文件。
- 仓库不保存签名密钥或密码。当前仓库已经配置并通过 CI 验证固定签名密钥及四个 GitHub Actions Secrets。

## 1. 固定签名密钥（本仓库已建立）

本仓库已经建立唯一的正式发布签名密钥，文件名为 `soulbot-release.jks`，别名为 `soulbot`，类型为 JKS，算法为 RSA 4096。

**不要再次执行 `keytool -genkeypair` 来生成新密钥。** 新生成的 JKS 会有不同的证书指纹，用户将无法用新 APK 覆盖安装旧签名版本。

当前 SoulBot 固定发布证书指纹（SHA-256）：

`FF:47:3F:AC:C9:19:E1:A9:F1:20:97:6B:D8:2C:45:61:01:E0:EE:98:14:9F:30:78:12:AA:C0:B2:B6:06:80:0F`

CI 会将正式 APK 的证书指纹与该值比较，防止误用另一把密钥。

可使用 JDK 的 `keytool` 查看本地备份的证书信息（仅供核对，不需要为了 GitHub 发版安装 JDK）：

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

本仓库的四个 Secret 已经由 GitHub Actions 实际读取并通过密钥库检查、Release 构建和 APK 证书指纹验证。不要在聊天中发送这些 Secret，也不要把密钥放入 GitHub Actions 普通变量（Variables）。配置完成后，删除下载目录和临时位置中的 `GITHUB_SECRETS.txt`（它包含 Base64 密钥和明文密码）；保留 JKS 的加密离线备份。

## 3. 发布新版本（无需本地开发环境）

正式发布前：

1. 在功能分支完成开发并通过 Pull Request 合入 `master`。
2. 更新 `android-app/app/build.gradle` 中的 `versionName` 和 `versionCode`。每次对外发布必须提高 `versionCode`；版本名称使用完整语义化版本，例如 `2.1.0`。
3. 同步更新 `CHANGELOG.md` 和相关文档。
4. 确认 `master` 最新提交已经通过 Debug CI，并完成必要的真机验收。
5. 确认四个 GitHub Secrets 仍然配置正确。

### 推荐：直接在 GitHub 网页发版

1. 打开仓库的 **Actions** 页面。
2. 在左侧选择 **Android Release**。
3. 点击 **Run workflow**。
4. `Use workflow from` 选择 `master`。
5. `Release tag` 输入与 Gradle `versionName` 一致的 Tag，例如 `v2.1.0`。
6. 点击 **Run workflow**。

工作流会自动检查版本号、密钥和证书指纹，运行测试、构建并签名 APK，最后创建 Tag 与 GitHub Release，上传 APK 和 SHA-256 校验文件。无需在自己的电脑安装 Git、JDK 或 Android Studio。

如果 Tag 已经存在、版本号不一致、Secrets 缺失或证书指纹不一致，工作流会终止，不会发布安装包。

### 可选：通过 Git 命令推送 Tag

已有 Git 环境的开发者也可以在已合入 `master` 的提交上创建并推送 Tag：

```bash
git switch master
git pull --ff-only
git tag -a v2.1.0 -m "SoulBot 2.1.0"
git push origin v2.1.0
```

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
