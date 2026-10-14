# Keep the bridge's JavaScript interface methods callable from local HTML assets.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes *Annotation*
# Keep JSON constructor-independent parsed structures unchanged.
-dontnote org.json.**
