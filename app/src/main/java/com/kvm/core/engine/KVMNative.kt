package com.kvm.core.engine

/**
 * KVMNative — Kotlin bridge to the NDK layer.
 *
 * All native methods declared here correspond 1-to-1 with the JNI implementations
 * in jni_bridge.cpp.  The companion object loads the shared library on first access.
 *
 * Crash-safety rule: every call site MUST check [isLibraryLoaded] before invoking
 * any external function.  If the .so fails to load (SELinux, ABI mismatch, linker
 * error), the external declarations are still present but calling them throws
 * UnsatisfiedLinkError.  The guard prevents that from propagating uncaught.
 */
object KVMNative {

    /**
     * True if and only if kvm_native.so loaded without error.
     * Checked by KVMEngine before every native call.
     */
    @Volatile
    var isLibraryLoaded: Boolean = false
        private set

    init {
        isLibraryLoaded = runCatching {
            System.loadLibrary("kvm_native")
            true
        }.getOrElse { t ->
            android.util.Log.e(
                "KVMNative",
                "Failed to load kvm_native.so — native VFS hooks, PLT hooks and inline " +
                "hooks are DISABLED for this session.  The app will still run but virtual " +
                "app file-system isolation will fall back to Java-level redirection.",
                t
            )
            false
        }
    }

    // ── Engine lifecycle ────────────────────────────────────────────────────

    /** One-time initialisation.  Must be called before any other native API. */
    @JvmStatic external fun nativeInit(hostPackage: String, virtualDataRoot: String): Boolean

    // ── VFS ──────────────────────────────────────────────────────────────────

    /** Install file-system redirect hooks for a specific virtual app instance. */
    @JvmStatic external fun nativeInstallVfsHooks(
        hostPackage:     String,
        guestPackage:    String,
        userId:          String,
        virtualDataRoot: String
    ): Boolean

    /** Remove VFS hooks (called when a virtual process exits). */
    @JvmStatic external fun nativeRemoveVfsHooks()

    /** Translate a guest path to its host-side isolated equivalent. */
    @JvmStatic external fun nativeTranslatePath(guestPath: String): String

    // ── Hook API ──────────────────────────────────────────────────────────────

    /** Install a PLT/GOT hook in `soName` for `symbolName` → `hookFuncPtr`. */
    @JvmStatic external fun nativeHookPlt(
        soName:      String,
        symbolName:  String,
        hookFuncPtr: Long
    ): Boolean

    // ── Memory introspection ─────────────────────────────────────────────────

    /** Return the load base address of a mapped .so, or 0 if not found. */
    @JvmStatic external fun nativeFindLoadBase(soName: String): Long

    // ── Safe wrappers ─────────────────────────────────────────────────────────
    // These never throw UnsatisfiedLinkError; they check isLibraryLoaded first.

    fun safeNativeInit(hostPackage: String, virtualDataRoot: String): Boolean {
        if (!isLibraryLoaded) return false
        return runCatching { nativeInit(hostPackage, virtualDataRoot) }.getOrDefault(false)
    }

    fun safeInstallVfsHooks(
        hostPackage:     String,
        guestPackage:    String,
        userId:          String,
        virtualDataRoot: String
    ): Boolean {
        if (!isLibraryLoaded) return false
        return runCatching {
            nativeInstallVfsHooks(hostPackage, guestPackage, userId, virtualDataRoot)
        }.getOrDefault(false)
    }

    fun safeRemoveVfsHooks() {
        if (!isLibraryLoaded) return
        runCatching { nativeRemoveVfsHooks() }
    }

    fun safeTranslatePath(guestPath: String): String {
        if (!isLibraryLoaded) return guestPath
        return runCatching { nativeTranslatePath(guestPath) }.getOrDefault(guestPath)
    }

    fun safeHookPlt(soName: String, symbolName: String, hookFuncPtr: Long): Boolean {
        if (!isLibraryLoaded) return false
        return runCatching { nativeHookPlt(soName, symbolName, hookFuncPtr) }.getOrDefault(false)
    }

    fun safeFindLoadBase(soName: String): Long {
        if (!isLibraryLoaded) return 0L
        return runCatching { nativeFindLoadBase(soName) }.getOrDefault(0L)
    }
}
