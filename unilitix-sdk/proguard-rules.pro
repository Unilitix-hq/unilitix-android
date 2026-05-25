# Public SDK classes
-keep class io.unilitix.sdk.Unilitix { *; }
-keep class io.unilitix.sdk.UnilitixConfig { *; }
-keep class io.unilitix.sdk.UnilitixConfig$Builder { *; }
-keep class io.unilitix.sdk.UnilitixPrivate { *; }
-keepclassmembers class ** {
    @io.unilitix.sdk.UnilitixPrivate *;
}

# Room — entities, DAOs, and database must survive shrinking
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao interface *
-keepclassmembers @androidx.room.Entity class * { *; }

# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.**

# WorkManager
-keep class androidx.work.** { *; }
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.CoroutineWorker

# Keep enums
-keepclassmembers enum io.unilitix.sdk.** { *; }
