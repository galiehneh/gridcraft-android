# JS bridge methods are looked up reflectively by the WebView.
-keepattributes *Annotation*
-keep class com.galiehneh.gridcraft.AndroidBridge {
    @android.webkit.JavascriptInterface <methods>;
}
