# Unilitix SDK — public API surface
-keep public class io.unilitix.sdk.Unilitix { *; }
-keep public class io.unilitix.sdk.UnilitixConfig { *; }
-keep public class io.unilitix.sdk.UnilitixConfig$Builder { *; }
-keep public enum io.unilitix.sdk.UnilitixConsent { *; }

# @UnilitixPrivate is read at runtime with Class.isAnnotationPresent on the
# app's own View classes. Without these rules R8 (full mode especially) may
# drop the annotation attribute, or merge/inline an annotated class, and the
# view is then neither masked in replay nor excluded from taps.
-keepattributes RuntimeVisibleAnnotations
-keep @interface io.unilitix.sdk.UnilitixPrivate
# Annotated classes may still be renamed or removed when unused, but keep
# their annotation and stay distinct classes (no class merging).
-keep,allowobfuscation,allowshrinking @io.unilitix.sdk.UnilitixPrivate class *
-keepclassmembers,allowobfuscation class * {
    @io.unilitix.sdk.UnilitixPrivate <fields>;
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

# Room: only the SDK's own database (io.unilitix.sdk.storage). Room's own
# consumer rules cover the generated *_Impl lookup; these rules used to keep
# every Room database, entity and DAO of the host app too (1.10.2: narrowed).
-keep class io.unilitix.sdk.storage.** extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class io.unilitix.sdk.storage.** { *; }
-keep @androidx.room.Dao interface io.unilitix.sdk.storage.** { *; }

# OkHttp, Okio and kotlinx.coroutines ship their own consumer rules; the
# blanket dontwarn rules for OkHttp, Okio and kotlinx.coroutines that used to
# be here hid real warnings in the host app's own code paths (removed 1.10.2).

# androidx.security:security-crypto (encrypted storage) brings in Tink, which
# refers to compile-only annotations (Error Prone, JSR-305). Without these
# rules R8 stops every minified release build of the host app with
# "Missing class com.google.errorprone.annotations.*" (1.11.2).
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
