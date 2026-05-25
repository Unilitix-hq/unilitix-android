# Unilitix SDK — public API surface
-keep public class io.unilitix.sdk.Unilitix { *; }
-keep public class io.unilitix.sdk.UnilitixConfig { *; }
-keep public class io.unilitix.sdk.UnilitixConfig$Builder { *; }
-keep public @interface io.unilitix.sdk.UnilitixPrivate
-keepclassmembers class ** {
    @io.unilitix.sdk.UnilitixPrivate *;
}

# Keep enums
-keepclassmembers enum io.unilitix.sdk.** { *; }

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
