# Unilitix SDK — public API surface
-keep public class io.unilitix.sdk.Unilitix { *; }
-keep public class io.unilitix.sdk.UnilitixConfig { *; }
-keep public class io.unilitix.sdk.UnilitixConfig$Builder { *; }
-keep public @interface io.unilitix.sdk.UnilitixPrivate
-keep public enum io.unilitix.sdk.UnilitixConsent { *; }
-keepclassmembers class ** {
    @io.unilitix.sdk.UnilitixPrivate *;
}

# Native crash capture (API 21-29): libunilitix-native.so binds these JNI
# methods by class and method name.
-keep class io.unilitix.sdk.capture.NativeSignalHandler {
    native <methods>;
}

# Keep enums
-keepclassmembers enum io.unilitix.sdk.** { *; }

# Jetpack Compose is compileOnly: an app without Compose must still shrink.
# (The Compose walk catches the NoClassDefFoundError at runtime.)
-dontwarn androidx.compose.**

# Unilitix.nativeCrashCaptureActive() is looked up by name by the Unilitix
# Flutter plugin (2.5.0+); kept by the Unilitix rule above, restated so it is
# not lost if that rule is narrowed.
-keep class io.unilitix.sdk.Unilitix {
    public static boolean nativeCrashCaptureActive();
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao interface *

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.**
