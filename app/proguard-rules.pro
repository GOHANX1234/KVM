##──────────────────────────────────────────────────────────────────────────────
## KVM ProGuard / R8 rules
##──────────────────────────────────────────────────────────────────────────────

# ── Android framework reflection ───────────────────────────────────────────────
# We access hidden APIs by name via reflection; R8 must not rename or remove them.

# ActivityThread internal fields
-keep class android.app.ActivityThread {
    public static android.app.ActivityThread currentActivityThread();
    android.app.Instrumentation mInstrumentation;
    android.content.pm.IPackageManager sPackageManager;
}

# ActivityManager singleton (IActivityManager hook)
-keep class android.app.ActivityManager {
    static android.util.Singleton IActivityManagerSingleton;
}

-keep class android.util.Singleton {
    java.lang.Object mInstance;
}

# IActivityManager + IPackageManager interfaces (we create dynamic proxies)
-keep interface android.app.IActivityManager { *; }
-keep interface android.content.pm.IPackageManager { *; }

# ApplicationPackageManager (we set mPM via reflection)
-keep class android.app.ApplicationPackageManager {
    android.content.pm.IPackageManager mPM;
}

# ── KVM Core — must never be obfuscated ───────────────────────────────────────

-keep class com.kvm.core.** { *; }
-keep class com.kvm.stub.**  { *; }
-keep class com.kvm.di.**    { *; }
-keep class com.kvm.KVMApplication { *; }

# ── JNI — native method names must be preserved ───────────────────────────────
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep all classes with @Keep annotation
-keep @androidx.annotation.Keep class * { *; }
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}

# ── Hilt ──────────────────────────────────────────────────────────────────────
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep @dagger.hilt.android.AndroidEntryPoint class * { *; }
-keep @dagger.hilt.android.HiltAndroidApp class * { *; }

# ── Room ──────────────────────────────────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao interface *

# ── Navigation component ──────────────────────────────────────────────────────
-keep class androidx.navigation.** { *; }

# ── Kotlin coroutines ─────────────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# ── Coil ──────────────────────────────────────────────────────────────────────
-keep class coil.** { *; }

# ── Gson ──────────────────────────────────────────────────────────────────────
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonDeserializer
-keep class * implements com.google.gson.JsonSerializer

# ── General Android ───────────────────────────────────────────────────────────
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*
-keepattributes Exceptions,InnerClasses,Signature,Deprecated,EnclosingMethod

# Remove verbose logging in release
-assumenosideeffects class timber.log.Timber {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
}
