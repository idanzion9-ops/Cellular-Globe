# The page talks to the app through @JavascriptInterface, so keep those members.
-keepclassmembers class com.idanzion.cellularglobe.** {
    @android.webkit.JavascriptInterface <methods>;
}
