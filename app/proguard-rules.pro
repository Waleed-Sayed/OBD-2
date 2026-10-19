# Keep every method exposed to the WebView through @JavascriptInterface.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

-keepattributes JavascriptInterface
