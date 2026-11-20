# Keep JavascriptInterface bridge methods invoked from WebView blob export hook
-keepclassmembers class io.github.designarena.wrapper.BlobExportBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep BuildConfig
-keep class io.github.designarena.wrapper.BuildConfig { *; }
