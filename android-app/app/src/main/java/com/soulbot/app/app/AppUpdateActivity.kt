package com.soulbot.app.app

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import com.soulbot.app.R
import com.soulbot.app.update.ReleaseInfo
import com.soulbot.app.update.UpdateCheckResult
import com.soulbot.app.update.UpdateRepository
import java.io.File
import java.util.concurrent.Executors

class AppUpdateActivity : BaseSettingsActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var tvCurrentVersion: TextView
    private lateinit var tvLatestVersion: TextView
    private lateinit var tvPublishedAt: TextView
    private lateinit var tvUpdateStatus: TextView
    private lateinit var tvReleaseNotes: TextView
    private lateinit var tvDownloadProgress: TextView
    private lateinit var progressDownload: ProgressBar
    private lateinit var btnCheckUpdate: MaterialButton
    private lateinit var btnDownloadUpdate: MaterialButton
    private lateinit var btnInstallUpdate: MaterialButton

    private var latestResult: UpdateCheckResult? = null
    private var downloadedApk: File? = null
    private var busy = false
    private var versionBeforeInstall: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_update)
        setupToolbar("版本更新")
        setupPrimaryNavigation(R.id.nav_settings)

        tvCurrentVersion = findViewById(R.id.tvCurrentVersion)
        tvLatestVersion = findViewById(R.id.tvLatestVersion)
        tvPublishedAt = findViewById(R.id.tvPublishedAt)
        tvUpdateStatus = findViewById(R.id.tvUpdateStatus)
        tvReleaseNotes = findViewById(R.id.tvReleaseNotes)
        tvDownloadProgress = findViewById(R.id.tvDownloadProgress)
        progressDownload = findViewById(R.id.progressDownload)
        btnCheckUpdate = findViewById(R.id.btnCheckUpdate)
        btnDownloadUpdate = findViewById(R.id.btnDownloadUpdate)
        btnInstallUpdate = findViewById(R.id.btnInstallUpdate)

        btnCheckUpdate.setOnClickListener { checkForUpdates() }
        btnDownloadUpdate.setOnClickListener { downloadUpdate() }
        btnInstallUpdate.setOnClickListener { installUpdate() }

        tvCurrentVersion.text = "当前版本：${UpdateRepository.currentVersionName(this)}"
        tvUpdateStatus.text = "正在检查 GitHub 正式版本……"
        tvReleaseNotes.text = "检查完成后，这里会显示版本更新说明。"
        btnDownloadUpdate.visibility = View.GONE
        btnInstallUpdate.visibility = View.GONE
        progressDownload.visibility = View.GONE
        checkForUpdates()
    }

    override fun onResume() {
        super.onResume()
        val file = downloadedApk
        if (file != null && file.isFile) {
            btnInstallUpdate.isEnabled = true
            if (!packageManager.canRequestPackageInstalls()) {
                tvUpdateStatus.text = "APK 已校验。请允许 SoulBot 安装未知应用后继续。"
            }
        }
        val expected = versionBeforeInstall
        if (expected != null) {
            val installed = runCatching { UpdateRepository.currentVersionName(this) }.getOrNull()
            if (installed != null && installed != expected) {
                tvCurrentVersion.text = "当前版本：$installed"
                tvUpdateStatus.text = "更新完成，已安装 $installed"
                versionBeforeInstall = null
            }
        }
    }

    private fun checkForUpdates() {
        if (busy) return
        busy = true
        btnCheckUpdate.isEnabled = false
        btnDownloadUpdate.isEnabled = false
        btnInstallUpdate.isEnabled = false
        tvUpdateStatus.text = "正在连接 GitHub 检查正式版本……"
        executor.execute {
            try {
                val result = UpdateRepository.checkLatest(applicationContext)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    busy = false
                    btnCheckUpdate.isEnabled = true
                    latestResult = result
                    renderResult(result)
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    busy = false
                    btnCheckUpdate.isEnabled = true
                    tvUpdateStatus.text = "检查失败：${error.message ?: "网络或 GitHub 服务暂不可用"}"
                    showMessage("检查更新失败，请检查网络后重试。")
                }
            }
        }
    }

    private fun renderResult(result: UpdateCheckResult) {
        val release = result.release
        tvCurrentVersion.text = "当前版本：${result.installedVersion}"
        tvLatestVersion.text = "最新正式版：${release.versionName}"
        tvPublishedAt.text = "发布时间：${release.publishedAt.take(10).ifBlank { "未知" }} · APK ${UpdateRepository.formatFileSize(release.apkSizeBytes)}"
        tvReleaseNotes.text = release.releaseNotes.ifBlank { "该版本没有提供更新说明。" }

        if (result.updateAvailable) {
            tvUpdateStatus.text = "发现新版本，可以下载并安装。"
            btnDownloadUpdate.visibility = View.VISIBLE
            btnDownloadUpdate.text = "下载更新 · ${release.versionName}"
            btnDownloadUpdate.isEnabled = true
        } else {
            tvUpdateStatus.text = if (UpdateRepository.currentVersionName(this) == release.versionName) {
                "已是最新正式版本。"
            } else {
                "当前版本高于或等于 GitHub 最新正式版。"
            }
            btnDownloadUpdate.visibility = View.GONE
            btnInstallUpdate.visibility = View.GONE
        }
    }

    private fun downloadUpdate() {
        val release: ReleaseInfo = latestResult?.release ?: return
        if (busy) return
        busy = true
        downloadedApk = null
        btnDownloadUpdate.isEnabled = false
        btnCheckUpdate.isEnabled = false
        btnInstallUpdate.visibility = View.GONE
        progressDownload.visibility = View.VISIBLE
        progressDownload.isIndeterminate = false
        progressDownload.progress = 0
        tvDownloadProgress.text = "准备下载……"
        tvUpdateStatus.text = "正在下载官方 APK。"

        executor.execute {
            try {
                val file = UpdateRepository.downloadAndVerify(applicationContext, release) { downloaded, total ->
                    val percent = if (total > 0L) ((downloaded * 100L) / total).toInt().coerceIn(0, 100) else 0
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            progressDownload.progress = percent
                            tvDownloadProgress.text =
                                "${UpdateRepository.formatFileSize(downloaded)} / ${UpdateRepository.formatFileSize(total)} · $percent%"
                        }
                    }
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    busy = false
                    downloadedApk = file
                    btnCheckUpdate.isEnabled = true
                    btnDownloadUpdate.isEnabled = true
                    btnDownloadUpdate.visibility = View.GONE
                    btnInstallUpdate.visibility = View.VISIBLE
                    btnInstallUpdate.isEnabled = true
                    progressDownload.progress = 100
                    tvDownloadProgress.text = "下载完成，SHA-256、包名、版本和签名均已验证。"
                    tvUpdateStatus.text = if (btnInstallUpdate.isEnabled) {
                        "APK 已通过安全校验，可以安装。"
                    } else {
                        "APK 已通过安全校验。还需要授予安装未知应用权限。"
                    }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    busy = false
                    btnCheckUpdate.isEnabled = true
                    btnDownloadUpdate.isEnabled = true
                    tvUpdateStatus.text = "下载或校验失败：${error.message ?: "未知错误"}"
                    tvDownloadProgress.text = "未安装任何文件。"
                    progressDownload.visibility = View.GONE
                }
            }
        }
    }

    private fun installUpdate() {
        val apk = downloadedApk?.takeIf(File::isFile) ?: run {
            showMessage("请先下载并完成 APK 校验。")
            return
        }
        if (SoulBotService.running) {
            AlertDialog.Builder(this)
                .setTitle("请先停止自动任务")
                .setMessage("当前自动化任务仍在运行。为避免安装时中断聊天或队列处理，请先从悬浮控制器停止任务，再回来安装更新。")
                .setPositiveButton("知道了", null)
                .show()
            return
        }
        if (!packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(this)
                .setTitle("需要安装权限")
                .setMessage("Android 要求用户允许 SoulBot 安装自己下载的 APK。开启权限后返回此页面，再点击安装更新。")
                .setNegativeButton("取消", null)
                .setPositiveButton("前往设置") { _, _ ->
                    runCatching {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:$packageName"),
                            ),
                        )
                    }.onFailure { showMessage("无法打开安装权限设置，请在系统设置中手动允许。") }
                }
                .show()
            return
        }

        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newUri(contentResolver, "SoulBot update APK", uri)
            }
            versionBeforeInstall = UpdateRepository.currentVersionName(this)
            startActivity(intent)
        } catch (error: Exception) {
            showMessage("无法启动系统安装器：${error.message ?: "未知错误"}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }
}
