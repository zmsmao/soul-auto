package com.soulbot.app.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class ReleaseInfo(
    val tagName: String,
    val versionName: String,
    val releaseName: String,
    val releaseNotes: String,
    val publishedAt: String,
    val releaseUrl: String,
    val apkUrl: String,
    val checksumUrl: String?,
    val apkSha256: String?,
    val apkSizeBytes: Long,
)

data class UpdateCheckResult(
    val installedVersion: String,
    val release: ReleaseInfo,
    val updateAvailable: Boolean,
)

object UpdateVersionPolicy {
    private val versionPattern = Regex("""^\d+(?:\.\d+){1,3}$""")

    fun isValidVersion(value: String): Boolean = versionPattern.matches(value)

    fun compare(left: String, right: String): Int {
        require(isValidVersion(left) && isValidVersion(right)) { "Invalid version format" }
        val leftParts = left.split('.').map(String::toLong)
        val rightParts = right.split('.').map(String::toLong)
        val size = maxOf(leftParts.size, rightParts.size)
        for (index in 0 until size) {
            val a = leftParts.getOrElse(index) { 0L }
            val b = rightParts.getOrElse(index) { 0L }
            if (a != b) return a.compareTo(b)
        }
        return 0
    }

    fun isNewer(latest: String, installed: String): Boolean =
        isValidVersion(latest) && isValidVersion(installed) && compare(latest, installed) > 0
}

object UpdateRepository {
    private const val RELEASE_API =
        "https://api.github.com/repos/zmsmao/soul-pilot/releases/latest"
    private const val RELEASE_DOWNLOAD_PREFIX =
        "https://github.com/zmsmao/soul-pilot/releases/download/"
    private const val MAX_APK_BYTES = 120L * 1024L * 1024L
    private const val OFFICIAL_CERT_SHA256 =
        "ff473facc919e1a9f120976bd82c456101e0ee98149f307812aac0b2b606800f"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun checkLatest(context: Context): UpdateCheckResult {
        val installed = packageInfo(context, context.packageName, 0)
        val installedVersion = installed.versionName.orEmpty()
        if (!UpdateVersionPolicy.isValidVersion(installedVersion)) {
            throw IOException("当前应用版本号格式无法识别")
        }
        val release = fetchLatestRelease()
        return UpdateCheckResult(
            installedVersion = installedVersion,
            release = release,
            updateAvailable = UpdateVersionPolicy.isNewer(release.versionName, installedVersion),
        )
    }

    private fun fetchLatestRelease(): ReleaseInfo {
        val request = Request.Builder()
            .url(RELEASE_API)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "SoulBot-Android-Updater")
            .get()
            .build()

        val jsonText = executeText(request, maxChars = 1_000_000)
        val root = JSONObject(jsonText)
        if (root.optBoolean("draft", false) || root.optBoolean("prerelease", false)) {
            throw IOException("GitHub 返回的版本不是正式发行版")
        }
        val tagName = root.optString("tag_name")
        if (!tagName.startsWith("v")) throw IOException("Release 标签格式无效")
        val versionName = tagName.removePrefix("v")
        if (!UpdateVersionPolicy.isValidVersion(versionName)) {
            throw IOException("Release 版本号格式无效")
        }
        val assets = root.optJSONArray("assets") ?: JSONArray()
        var apkAsset: JSONObject? = null
        var checksumAsset: JSONObject? = null
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            when (asset.optString("name")) {
                "SoulBot-$versionName.apk" -> apkAsset = asset
                "SoulBot-$versionName.apk.sha256" -> checksumAsset = asset
            }
        }
        val apk = apkAsset ?: throw IOException("最新版本没有找到正式 APK")
        val apkUrl = apk.optString("browser_download_url")
        if (!isAllowedReleaseAsset(apkUrl)) throw IOException("APK 下载地址不属于官方 Release")
        val size = apk.optLong("size", 0L)
        if (size <= 0L || size > MAX_APK_BYTES) throw IOException("APK 文件大小异常")

        val rawDigest = apk.optString("digest")
        val apiDigest = rawDigest.removePrefix("sha256:").lowercase()
            .takeIf { it.matches(Regex("^[0-9a-f]{64}$")) }
        val checksumUrl = checksumAsset?.optString("browser_download_url")
            ?.let { candidate -> candidate.takeIf { isAllowedReleaseAsset(it) } }

        return ReleaseInfo(
            tagName = tagName,
            versionName = versionName,
            releaseName = root.optString("name").ifBlank { "SoulBot $versionName" },
            releaseNotes = root.optString("body").take(12_000),
            publishedAt = root.optString("published_at"),
            releaseUrl = root.optString("html_url"),
            apkUrl = apkUrl,
            checksumUrl = checksumUrl,
            apkSha256 = apiDigest,
            apkSizeBytes = size,
        )
    }

    fun downloadAndVerify(
        context: Context,
        release: ReleaseInfo,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): File {
        if (!isAllowedReleaseAsset(release.apkUrl)) throw IOException("拒绝非官方 APK 下载地址")
        if (!UpdateVersionPolicy.isValidVersion(release.versionName)) throw IOException("版本号无效")
        if (release.apkSizeBytes !in 1..MAX_APK_BYTES) throw IOException("APK 文件大小超出安全范围")

        val directory = File(context.cacheDir, "updates").apply {
            if (!exists() && !mkdirs()) throw IOException("无法创建更新缓存目录")
        }
        val partial = File(directory, "SoulBot-${release.versionName}.apk.part")
        val completed = File(directory, "SoulBot-${release.versionName}.apk")
        partial.delete()
        completed.delete()

        val request = Request.Builder()
            .url(release.apkUrl)
            .header("User-Agent", "SoulBot-Android-Updater")
            .get()
            .build()
        val call = client.newCall(request)
        call.execute().use { response ->
            if (!response.isSuccessful) throw IOException("APK 下载失败：HTTP ${response.code}")
            val body = response.body ?: throw IOException("下载内容为空")
            val contentLength = body.contentLength()
            if (contentLength > MAX_APK_BYTES) throw IOException("APK 文件过大")
            body.byteStream().use { input ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var downloaded = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        downloaded += count
                        if (downloaded > MAX_APK_BYTES || downloaded > release.apkSizeBytes + 1024L) {
                            throw IOException("下载文件大小超过 Release 声明")
                        }
                        output.write(buffer, 0, count)
                        onProgress(downloaded, if (contentLength > 0L) contentLength else release.apkSizeBytes)
                    }
                    output.flush()
                    if (downloaded != release.apkSizeBytes) {
                        throw IOException("下载文件大小与 Release 声明不一致")
                    }
                }
            }
        }

        val expectedDigest = release.apkSha256 ?: fetchSidecarDigest(release.checksumUrl)
        val actualDigest = sha256(partial)
        if (!actualDigest.equals(expectedDigest, ignoreCase = true)) {
            partial.delete()
            throw IOException("APK SHA-256 校验失败，已删除下载文件")
        }
        if (!partial.renameTo(completed)) {
            partial.delete()
            throw IOException("无法保存已校验的 APK")
        }

        try {
            verifyPackageAndSignature(context, completed, release)
        } catch (error: Exception) {
            completed.delete()
            throw error
        }
        return completed
    }

    private fun fetchSidecarDigest(url: String?): String {
        if (url == null || !isAllowedReleaseAsset(url)) {
            throw IOException("Release 未提供可验证的 APK SHA-256")
        }
        val text = executeText(
            Request.Builder().url(url).header("User-Agent", "SoulBot-Android-Updater").get().build(),
            maxChars = 512,
        ).trim()
        val digest = text.split(Regex("\\s+")).firstOrNull().orEmpty().lowercase()
        if (!digest.matches(Regex("^[0-9a-f]{64}$"))) {
            throw IOException("Release SHA-256 文件格式无效")
        }
        return digest
    }

    private fun verifyPackageAndSignature(context: Context, apk: File, release: ReleaseInfo) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val archive = packageManager(context).getPackageArchiveInfo(apk.absolutePath, flags)
            ?: throw IOException("下载文件不是有效的 Android APK")
        if (archive.packageName != context.packageName) {
            throw IOException("APK 包名不匹配，拒绝安装")
        }
        if (archive.versionName != release.versionName) {
            throw IOException("APK 内部版本号与 Release 标签不一致")
        }

        val installed = packageInfo(context, context.packageName, flags)
        val installedVersion = installed.versionName.orEmpty()
        if (!UpdateVersionPolicy.isNewer(archive.versionName.orEmpty(), installedVersion)) {
            throw IOException("下载的版本并不高于当前安装版本")
        }
        if (versionCode(archive) <= versionCode(installed)) {
            throw IOException("APK versionCode 未递增，拒绝安装")
        }

        val installedSigners = signerDigests(installed)
        val archiveSigners = signerDigests(archive)
        if (OFFICIAL_CERT_SHA256 !in installedSigners) {
            throw IOException("当前安装包不是项目正式签名版本，请先完成旧版迁移")
        }
        if (OFFICIAL_CERT_SHA256 !in archiveSigners || archiveSigners != installedSigners) {
            throw IOException("APK 签名与当前安装版本不一致，拒绝安装")
        }
    }

    private fun signerDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.toList().orEmpty()
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.toList().orEmpty()
        }
        if (signatures.isEmpty()) throw IOException("无法读取 APK 签名")
        return signatures.map { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { byte -> "%02x".format(byte) }
        }.toSet()
    }

    private fun packageInfo(context: Context, packageName: String, flags: Int): PackageInfo =
        try {
            @Suppress("DEPRECATION")
            packageManager(context).getPackageInfo(packageName, flags)
        } catch (error: PackageManager.NameNotFoundException) {
            throw IOException("无法读取当前应用信息", error)
        }

    private fun packageManager(context: Context): PackageManager = context.packageManager

    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else info.versionCode.toLong()

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun executeText(request: Request, maxChars: Int): String {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("GitHub 请求失败：HTTP ${response.code}")
            val body = response.body ?: throw IOException("GitHub 返回内容为空")
            val text = body.string()
            if (text.length > maxChars) throw IOException("GitHub 返回内容超出限制")
            return text
        }
    }

    private fun isAllowedReleaseAsset(url: String): Boolean =
        url.startsWith(RELEASE_DOWNLOAD_PREFIX) && url.length <= 2048

    fun currentVersionName(context: Context): String =
        packageInfo(context, context.packageName, 0).versionName.orEmpty()

    fun formatFileSize(bytes: Long): String {
        if (bytes < 1024) return "${bytes} B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        return String.format("%.1f MB", kb / 1024.0)
    }

    private fun fetchLatestWithContext(context: Context) = checkLatest(context)

    fun checkAutomatically(context: Context, onUpdateAvailable: (String) -> Unit) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences("soulbot_update_state", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastSuccess = prefs.getLong("last_successful_check", 0L)
        val lastAttempt = prefs.getLong("last_attempt", 0L)
        if (now - lastSuccess < 24L * 60L * 60L * 1000L) return
        if (now - lastAttempt < 30L * 60L * 1000L) return
        prefs.edit().putLong("last_attempt", now).apply()

        Thread({
            try {
                val result = fetchLatestWithContext(appContext)
                prefs.edit().putLong("last_successful_check", System.currentTimeMillis()).apply()
                if (result.updateAvailable) {
                    val notified = prefs.getString("last_notified_tag", "").orEmpty()
                    if (notified != result.release.tagName) {
                        prefs.edit().putString("last_notified_tag", result.release.tagName).apply()
                        Handler(Looper.getMainLooper()).post {
                            onUpdateAvailable(result.release.versionName)
                        }
                    }
                }
            } catch (_: Exception) {
                // A failed automatic check is retried after a short cooldown; manual checks remain available.
            }
        }, "soulbot-update-check").start()
    }

}
