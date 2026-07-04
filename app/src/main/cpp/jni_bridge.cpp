#include "common.h"
#include "core/hook_engine.h"
#include "core/vfs_native.h"
#include "core/memory_utils.h"

/*
 * JNI Bridge — all Java-callable native methods live here.
 *
 * Kotlin signature:    external fun <name>(<args>): <returnType>
 * The native name:     Java_com_kvm_core_engine_KVMNative_<name>
 */

extern "C" {

// ─── Engine lifecycle ─────────────────────────────────────────────────────────

/**
 * Called once from KVMEngine when the host process starts.
 * Performs baseline initialisation and early hook setup.
 */
JNIEXPORT jboolean JNICALL
Java_com_kvm_core_engine_KVMNative_nativeInit(JNIEnv* env, jclass /* clazz */,
                                              jstring hostPackage,
                                              jstring virtualDataRoot)
{
    const char* pkg  = env->GetStringUTFChars(hostPackage,     nullptr);
    const char* root = env->GetStringUTFChars(virtualDataRoot, nullptr);

    KLOGI("KVMNative: init — host=%s  root=%s", pkg, root);

    // Store config; VFS hooks are installed per-virtual-app launch.
    kvm::VfsConfig cfg;
    cfg.hostPackage     = pkg;
    cfg.virtualDataRoot = root;
    // guestPackage / guestUserId populated at launch time via nativeStartVirtualApp.

    env->ReleaseStringUTFChars(hostPackage,     pkg);
    env->ReleaseStringUTFChars(virtualDataRoot, root);
    return JNI_TRUE;
}

// ─── VFS control ─────────────────────────────────────────────────────────────

/**
 * Install VFS redirect hooks for a specific virtual app instance.
 * Called by VirtualProcessManager right before the guest code runs.
 */
JNIEXPORT jboolean JNICALL
Java_com_kvm_core_engine_KVMNative_nativeInstallVfsHooks(JNIEnv* env, jclass,
                                                         jstring hostPkg,
                                                         jstring guestPkg,
                                                         jstring userId,
                                                         jstring dataRoot)
{
    auto toStr = [&](jstring js) -> std::string {
        if (!js) return {};
        const char* c = env->GetStringUTFChars(js, nullptr);
        std::string s = c;
        env->ReleaseStringUTFChars(js, c);
        return s;
    };

    kvm::VfsConfig cfg;
    cfg.hostPackage     = toStr(hostPkg);
    cfg.guestPackage    = toStr(guestPkg);
    cfg.guestUserId     = toStr(userId);
    cfg.virtualDataRoot = toStr(dataRoot);

    bool ok = kvm::vfsInstallHooks(cfg);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_kvm_core_engine_KVMNative_nativeRemoveVfsHooks(JNIEnv*, jclass)
{
    kvm::vfsRemoveHooks();
}

// ─── Path translation ─────────────────────────────────────────────────────────

/**
 * Translate a guest-side path to its host-side isolated equivalent.
 * Used from Kotlin for debugging and pre-create-directory calls.
 */
JNIEXPORT jstring JNICALL
Java_com_kvm_core_engine_KVMNative_nativeTranslatePath(JNIEnv* env, jclass,
                                                       jstring guestPath)
{
    if (!guestPath) return guestPath;
    const char* raw = env->GetStringUTFChars(guestPath, nullptr);
    std::string translated = kvm::vfsTranslatePath(raw);
    env->ReleaseStringUTFChars(guestPath, raw);
    return env->NewStringUTF(translated.c_str());
}

// ─── PLT hook API (exposed for Kotlin-driven hook registration) ───────────────

JNIEXPORT jboolean JNICALL
Java_com_kvm_core_engine_KVMNative_nativeHookPlt(JNIEnv* env, jclass,
                                                  jstring soName,
                                                  jstring symbolName,
                                                  jlong   hookFuncPtr)
{
    const char* so  = env->GetStringUTFChars(soName,     nullptr);
    const char* sym = env->GetStringUTFChars(symbolName, nullptr);
    void* hookFn    = reinterpret_cast<void*>(static_cast<uintptr_t>(hookFuncPtr));

    kvm::HookStatus s = kvm::hookPlt(so, sym, hookFn, nullptr);

    env->ReleaseStringUTFChars(soName,     so);
    env->ReleaseStringUTFChars(symbolName, sym);
    return (s == kvm::HookStatus::OK) ? JNI_TRUE : JNI_FALSE;
}

// ─── Memory / maps introspection ─────────────────────────────────────────────

JNIEXPORT jlong JNICALL
Java_com_kvm_core_engine_KVMNative_nativeFindLoadBase(JNIEnv* env, jclass,
                                                      jstring soName)
{
    const char* so = env->GetStringUTFChars(soName, nullptr);
    uintptr_t base = kvm::findLoadBase(so);
    env->ReleaseStringUTFChars(soName, so);
    return static_cast<jlong>(base);
}

// ─── JNI_OnLoad ──────────────────────────────────────────────────────────────

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /* reserved */) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    KLOGI("KVM native library loaded — JNI_VERSION_1_6");
    return JNI_VERSION_1_6;
}

} // extern "C"
