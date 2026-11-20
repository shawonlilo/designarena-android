package io.github.designarena.wrapper

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.view.View
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var rootContainer: View
    private lateinit var webView: WebView
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var offlineOverlay: View
    private lateinit var downloadBridge: DownloadBridge
    private lateinit var networkMonitor: NetworkMonitor

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var lastFailedUrl: String? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = filePathCallback
        filePathCallback = null
        if (callback == null) return@registerForActivityResult

        val uris = extractSelectedUris(result.resultCode, result.data)
        callback.onReceiveValue(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rootContainer = findViewById(R.id.rootContainer)
        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        offlineOverlay = findViewById(R.id.offlineOverlay)

        applySystemBarAndImeInsets()
        setupOfflineActions()
        setupWebView()
        setupBackNavigation()
        setupNetworkMonitor()

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(BuildConfig.BASE_URL)
        }

        checkForGitHubUpdate()
    }

    private fun applySystemBarAndImeInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(rootContainer) { view, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            windowInsets
        }
    }

    private fun setupOfflineActions() {
        findViewById<MaterialButton>(R.id.retryButton).setOnClickListener {
            retryCurrentPage()
        }
        findViewById<MaterialButton>(R.id.openBrowserButton).setOnClickListener {
            val target = lastFailedUrl ?: webView.url ?: BuildConfig.BASE_URL
            openCustomTab(target)
        }
    }

    private fun setupWebView() {
        WebViewConfigurator.configure(webView)

        downloadBridge = DownloadBridge(
            context = this,
            onStatusMessage = { msg ->
                runOnUiThread {
                    Snackbar.make(rootContainer, msg, Snackbar.LENGTH_SHORT).show()
                }
            },
            onFileReadyToShare = { uri, mime ->
                runOnUiThread {
                    Snackbar.make(rootContainer, R.string.action_share, Snackbar.LENGTH_LONG)
                        .setAction(R.string.action_share) {
                            startActivity(Intent.createChooser(downloadBridge.buildShareIntent(uri, mime), null))
                        }
                        .show()
                }
            }
        )

        webView.addJavascriptInterface(
            BlobExportBridge(downloadBridge) {
                runOnUiThread {
                    Snackbar.make(rootContainer, R.string.download_failed, Snackbar.LENGTH_SHORT).show()
                }
            },
            "DesignArenaExport"
        )

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            downloadBridge.handleDownload(webView, url, userAgent, contentDisposition, mimeType)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                return handleNavigation(request.url.toString())
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                CookieManager.getInstance().flush()
                if (lastFailedUrl == null) {
                    offlineOverlay.isVisible = false
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) {
                    lastFailedUrl = request.url.toString()
                    offlineOverlay.isVisible = true
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (newProgress in 1..99) {
                    progressBar.isVisible = true
                    progressBar.setProgressCompat(newProgress, true)
                } else {
                    progressBar.isVisible = false
                }
            }

            override fun onShowFileChooser(
                webView: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                // Cancel any dangling callback so WebView never permanently locks up.
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback

                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    val acceptTypes = params.acceptTypes.filter { it.isNotBlank() }
                    if (acceptTypes.isEmpty()) {
                        type = "*/*"
                    } else if (acceptTypes.size == 1) {
                        type = acceptTypes.first()
                    } else {
                        type = "*/*"
                        putExtra(Intent.EXTRA_MIME_TYPES, acceptTypes.toTypedArray())
                    }
                    putExtra(
                        Intent.EXTRA_ALLOW_MULTIPLE,
                        params.mode == FileChooserParams.MODE_OPEN_MULTIPLE
                    )
                }

                return runCatching {
                    fileChooserLauncher.launch(Intent.createChooser(intent, null))
                    true
                }.getOrElse {
                    filePathCallback?.onReceiveValue(null)
                    filePathCallback = null
                    false
                }
            }

            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                val tempWebView = WebView(this@MainActivity).apply {
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            popupView: WebView,
                            request: WebResourceRequest
                        ): Boolean {
                            val url = request.url.toString()
                            if (UrlRouter.route(url) == RouteDecision.LoadInWebView) {
                                webView.loadUrl(url)
                            } else {
                                handleNavigation(url)
                            }
                            popupView.destroy()
                            return true
                        }
                    }
                }
                transport.webView = tempWebView
                resultMsg.sendToTarget()
                return true
            }
        }
    }

    private fun handleNavigation(url: String): Boolean {
        return when (UrlRouter.route(url)) {
            RouteDecision.LoadInWebView -> false
            RouteDecision.OpenCustomTab -> {
                openCustomTab(url)
                true
            }
            RouteDecision.OpenExternalIntent -> {
                openExternalIntent(url)
                true
            }
            RouteDecision.Block -> true
        }
    }

    private fun openCustomTab(url: String) {
        val uri = Uri.parse(url)
        val customTabsIntent = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
        runCatching {
            customTabsIntent.launchUrl(this, uri)
        }.onFailure {
            openExternalIntent(url)
        }
    }

    private fun openExternalIntent(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Snackbar.make(rootContainer, R.string.no_external_app, Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (webView.canGoBack()) {
                        webView.goBack()
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )
    }

    private fun setupNetworkMonitor() {
        networkMonitor = NetworkMonitor(this) { isOnline ->
            runOnUiThread {
                if (isOnline && offlineOverlay.isVisible) {
                    retryCurrentPage()
                }
            }
        }
    }

    private fun retryCurrentPage() {
        val target = lastFailedUrl
        lastFailedUrl = null
        offlineOverlay.isVisible = false
        if (target != null) {
            webView.loadUrl(target)
        } else {
            webView.reload()
        }
    }

    private fun checkForGitHubUpdate() {
        if (BuildConfig.GITHUB_REPO.isBlank()) return
        lifecycleScope.launch {
            val update = UpdateChecker.checkForUpdate() ?: return@launch
            if (isFinishing || isDestroyed) return@launch

            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(getString(R.string.update_available_title, update.tagName))
                .setMessage(R.string.update_available_message)
                .setNegativeButton(R.string.action_later, null)
                .setPositiveButton(R.string.action_update) { _, _ ->
                    downloadAndInstallUpdate(update)
                }
                .show()
        }
    }

    private fun downloadAndInstallUpdate(update: ReleaseUpdate) {
        Snackbar.make(rootContainer, R.string.update_downloading, Snackbar.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val apkUri = UpdateChecker.downloadAndVerifyApk(this@MainActivity, update)
            if (apkUri != null) {
                startActivity(UpdateChecker.buildInstallIntent(apkUri))
            } else {
                Snackbar.make(rootContainer, R.string.update_failed, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun extractSelectedUris(resultCode: Int, data: Intent?): Array<Uri>? {
        if (resultCode != RESULT_OK || data == null) return null
        val clipData = data.clipData
        if (clipData != null && clipData.itemCount > 0) {
            return Array(clipData.itemCount) { index -> clipData.getItemAt(index).uri }
        }
        return data.data?.let { arrayOf(it) }
    }

    override fun onStart() {
        super.onStart()
        networkMonitor.start()
    }

    override fun onStop() {
        networkMonitor.stop()
        CookieManager.getInstance().flush()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onDestroy() {
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        webView.destroy()
        super.onDestroy()
    }
}
