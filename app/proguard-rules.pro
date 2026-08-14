# ReWeb R8 configuration.
#
# The app exposes no public API and reflects over almost nothing, so the default
# optimized rules do the heavy lifting. The entries below cover the few places
# where the framework instantiates our classes by name.

# Views inflated from XML are resolved reflectively by LayoutInflater.
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# Activities/Services/Receivers named in the manifest.
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.app.Application

# Strip verbose logging from release builds. ReWeb never logs URLs, cookies or
# credentials, but this guarantees debug-level chatter cannot survive shrinking.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}

# Keep source file/line info for readable crash reports while still obfuscating.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# androidx.media's MediaSessionCompat resolves some callbacks reflectively on
# older platform versions.
-keep class android.support.v4.media.** { *; }
-keep class androidx.media.** { *; }

# Robolectric/JUnit annotations must not trip R8 on the release path.
-dontwarn org.robolectric.**
-dontwarn org.jetbrains.annotations.**
