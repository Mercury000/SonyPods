# Kotlin
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static void check*(...);
    public static void throw*(...);
}

# libxposed API 102 entry/resource handling (official integration rules)
-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list

-repackageclasses
-allowaccessmodification
-overloadaggressively
-renamesourcefileattribute SourceFile

# Keep Xposed entry point
-keep class dev.sonypods.hook.HookEntry { *; }

# Android instantiates the Application by the manifest's binary name. Keep it
# stable because -repackageclasses must not rename the process bootstrap class.
-keep class dev.sonypods.SonyPodsApp { *; }

# Keep all hooker classes (referenced by name in Xposed framework)
-keep class dev.sonypods.hook.** { *; }

# Keep Parcelable data classes (used in broadcast extras)
-keep class dev.sonypods.utils.miuiStrongToast.data.** { *; }
