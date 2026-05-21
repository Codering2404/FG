# Keep Room entities
-keep class com.focusguard.app.data.** { *; }

# Keep JavaScript interface methods (called from WebView)
-keepclassmembers class com.focusguard.app.ui.MainActivity$AndroidBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep Firebase / Gson models
-keep class com.google.firebase.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn com.google.firebase.**

# Keep DeviceAdminReceiver
-keep public class com.focusguard.app.receivers.FocusDeviceAdminReceiver { *; }

# AdMob
-keep class com.google.android.gms.ads.** { *; }

# Gson
-keepattributes Signature
-keep class sun.misc.Unsafe { *; }
-keep class com.google.gson.** { *; }
