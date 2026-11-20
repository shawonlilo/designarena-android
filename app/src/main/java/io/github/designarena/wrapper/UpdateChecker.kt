package io.github.designarena.wrapper

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class ReleaseUpdate(
    val tagName: String,
    val apkDownloadUrl: String,
    val apkFileName: String,
    val expectedSha256: String?
)

/**
 * Lightweight GitHub Releases updater.
 * No-ops cleanly if BuildConfig.GITHUB_REPO is empty.
 */
object UpdateChecker {

    suspend fun checkForUpdate(
        githubRepo: String = BuildConfig.GITHUB_REPO,
        currentVersion: String = BuildConfig.VERSION_NAME
    ): ReleaseUpdate? = withContext(Dispatchers.IO) {
        val repo = githubRepo.trim()
        if (repo.isEmpty() || !repo.contains("/")) return@withContext null

        val endpoint = "https://api.github.com/repos/$repo/releases/latest"
        val jsonText = fetchText(endpoint) ?: return@withContext null
        val root = runCatching { JSONObject(jsonText) }.getOrNull() ?: return@withContext null

        val tagName = root.optString("tag_name").trim()
        if (tagName.isEmpty() || !VersionComparator.isNewer(tagName, currentVersion)) {
            return@withContext null
        }

        val assets = root.optJSONArray("assets") ?: return@withContext null
        var apkUrl: String? = null
        var apkName: String? = null
        var shaAssetUrl: String? = null

        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url")
            if (!url.startsWith("https://")) continue

            when {
                name.endsWith(".apk", ignoreCase = true) && apkUrl == null -> {
                    apkUrl = url
                    apkName = name
                }
                name.endsWith(".sha256", ignoreCase = true) || name.equals("SHA256SUMS.txt", ignoreCase = true) -> {
                    shaAssetUrl = url
                }
            }
        }

        if (apkUrl == null || apkName == null) return@withContext null

        val expectedSha = shaAssetUrl?.let { parseSha256ForFile(fetchText(it), apkName) }

        ReleaseUpdate(
            tagName = tagName,
            apkDownloadUrl = apkUrl,
            apkFileName = apkName,
            expectedSha256 = expectedSha
        )
    }

    suspend fun downloadAndVerifyApk(context: Context, update: ReleaseUpdate): Uri? =
        withContext(Dispatchers.IO) {
            val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
            updatesDir.listFiles()?.forEach { it.delete() }
            val targetFile = File(updatesDir, update.apkFileName)

            val downloaded = runCatching {
                val conn = (URL(update.apkDownloadUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                }
                conn.inputStream.use { input ->
                    targetFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                conn.disconnect()
                true
            }.getOrDefault(false)

            if (!downloaded || !targetFile.exists()) return@withContext null

            val expected = update.expectedSha256
            if (!expected.isNullOrBlank()) {
                val actual = sha256Hex(targetFile)
                if (!actual.equals(expected, ignoreCase = true)) {
                    targetFile.delete()
                    return@withContext null
                }
            }

            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                targetFile
            )
        }

    fun buildInstallIntent(apkUri: Uri): Intent {
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun fetchText(urlString: String): String? = runCatching {
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "designarena-android/${BuildConfig.VERSION_NAME}")
        }
        try {
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    internal fun parseSha256ForFile(content: String?, fileName: String): String? {
        if (content.isNullOrBlank()) return null
        return content.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && (it.endsWith(fileName) || it.matches(Regex("^[a-fA-F0-9]{64}$"))) }
            ?.split(Regex("\\s+"))
            ?.firstOrNull()
            ?.takeIf { it.matches(Regex("^[a-fA-F0-9]{64}$")) }
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
