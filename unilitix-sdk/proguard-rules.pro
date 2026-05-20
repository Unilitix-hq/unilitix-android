-keep class io.unilitix.sdk.Unilitix { *; }
-keep class io.unilitix.sdk.UnilitixConfig { *; }
-keep class io.unilitix.sdk.UnilitixPrivate { *; }
-keepclassmembers class * {
    @io.unilitix.sdk.UnilitixPrivate *;
}
