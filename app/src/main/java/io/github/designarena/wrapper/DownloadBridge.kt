package io.github.designarena.wrapper

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebView
import androidx.core.content.FileProvider
import java.io.File

/**
 * Handles both standard HTTPS downloads (via Android DownloadManager) and client-side
 * blob:/data: exports (common for HTML/code/ZIP exports in web apps) without requiring
 * broad storage permissions.
 */
class DownloadBridge(
    private val context: Context,
    private val onStatusMessage: (String) -> Unit,
    private val onFileReadyToShare: (Uri, String) -> Unit
) {

    fun handleDownload(
        webView: WebView,
        url: String,
        userAgent: String,
        contentDisposition: String,
        mimeType: String
    ) {
        val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            .ifBlank { "designarena-export.bin" }

        when {
            url.startsWith("blob:", ignoreCase = true) -> {
                fetchBlobAndSave(webView, url, fileName, mimeType)
            }
            url.startsWith("data:", ignoreCase = true) -> {
                saveDataUri(url, fileName, mimeType)
            }
            url.startsWith("https://", ignoreCase = true) -> {
                enqueueDownloadManager(url, userAgent, fileName, mimeType)
            }
            else -> {
                onStatusMessage(context.getString(R.string.download_failed))
            }
        }
    }

    private fun enqueueDownloadManager(
        url: String,
        userAgent: String,
        fileName: String,
        mimeType: String
    ) {
        runCatching {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle(fileName)
                setMimeType(mimeType)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                addRequestHeader("User-Agent", userAgent)
                val cookies = CookieManager.getInstance().getCookie(url)
                if (!cookies.isNullOrBlank()) {
                    addRequestHeader("Cookie", cookies)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                } else {
                    setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
                }
            }
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            onStatusMessage(context.getString(R.string.download_started, fileName))
        }.onFailure {
            onStatusMessage(context.getString(R.string.download_failed))
        }
    }

    private fun fetchBlobAndSave(
        webView: WebView,
        blobUrl: String,
        fileName: String,
        mimeType: String
    ) {
        val safeUrl = blobUrl.replace("\\", "\\\\").replace("'", "\\'")
        val safeName = fileName.replace("\\", "\\\\").replace("'", "\\'")
        val safeMime = mimeType.replace("\\", "\\\\").replace("'", "\\'")

        val js = """
            (function() {
                fetch('$safeUrl')
                    .then(function(r) { return r.blob(); })
                    .then(function(blob) {
                        var reader = new FileReader();
                        reader.onloadend = function() {
                            if (window.DesignArenaExport && reader.result) {
                                window.DesignArenaExport.onBlobReady(reader.result, '$safeName', '$safeMime');
                            }
                        };
                        reader.readAsDataURL(blob);
                    })
                    .catch(function() {
                        if (window.DesignArenaExport) {
                            window.DesignArenaExport.onBlobFailed();
                        }
                    });
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    internal fun saveDataUri(dataUri: String, fileName: String, fallbackMime: String) {
        val commaIndex = dataUri.indexOf(',')
        if (commaIndex == -1) {
            onStatusMessage(context.getString(R.string.download_failed))
            return
        }

        val header = dataUri.substring(5, commaIndex)
        val payload = dataUri.substring(commaIndex + 1)
        val detectedMime = header.substringBefore(';').ifBlank { fallbackMime.ifBlank { "application/octet-stream" } }
        val isBase64 = header.contains(";base64", ignoreCase = true)

        val bytes = runCatching {
            if (isBase64) Base64.decode(payload, Base64.DEFAULT) else Uri.decode(payload).toByteArray()
        }.getOrNull()

        if (bytes == null) {
            onStatusMessage(context.getString(R.string.download_failed))
            return
        }

        val savedUri = saveBytesToDownloads(bytes, fileName, detectedMime)
        if (savedUri != null) {
            onStatusMessage(context.getString(R.string.download_saved, fileName))
            onFileReadyToShare(savedUri, detectedMime)
        } else {
            onStatusMessage(context.getString(R.string.download_failed))
        }
    }

    private fun saveBytesToDownloads(bytes: ByteArray, fileName: String, mimeType: String): Uri? {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return null
                resolver.openOutputStream(uri)?.use { it.write(bytes) }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                uri
            } else {
                val exportsDir = File(context.cacheDir, "exports").apply { mkdirs() }
                val outFile = File(exportsDir, fileName)
                outFile.writeBytes(bytes)
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    outFile
                )
            }
        }.getOrNull()
    }

    fun buildShareIntent(uri: Uri, mimeType: String): Intent {
        return Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}

class BlobExportBridge(
    private val downloadBridge: DownloadBridge,
    private val onError: () -> Unit
) {
    @JavascriptInterface
    fun onBlobReady(dataUri: String, fileName: String, mimeType: String) {
        downloadBridge.saveDataUri(dataUri, fileName, mimeType)
    }

    @JavascriptInterface
    fun onBlobFailed() {
        onError()
    }
}
