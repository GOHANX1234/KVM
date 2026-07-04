#include "vfs_native.h"
#include "hook_engine.h"
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <atomic>
#include <mutex>
#include <fcntl.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <unistd.h>
#include <errno.h>

/*
 * VFS Native — libc-level file redirection via PLT/GOT hooks.
 *
 * Path translation rule:
 *   /data/data/<guestPkg>/...        → <virtualDataRoot>/data/user/<uid>/<guestPkg>/...
 *   /data/user/<uid>/<guestPkg>/...  → <virtualDataRoot>/data/user/<uid>/<guestPkg>/...
 *
 * Hook lifecycle:
 *   vfsInstallHooks() — patches GOT entries in libc.so; stores orig_* pointers.
 *   vfsRemoveHooks()  — nulls orig_* so hooks become transparent passthrough via
 *                       direct syscall fallback.  GOT entries remain patched;
 *                       every hook function is written to be CORRECT with a null
 *                       orig_* (it falls back to a direct kernel syscall, not -1).
 *
 * Why we keep hooks installed in GOT after remove:
 *   Restoring GOT entries after-the-fact from another thread is a TOCTOU race
 *   (another thread may be inside the hook at the moment we restore).  The safe
 *   approach is to leave the GOT entries pointing at our hooks and make the hooks
 *   transparent when orig_* is null — effectively a no-op interception.
 *   Calling the libc stub that would be in the GOT slot is not required because
 *   our syscall fallbacks are functionally equivalent on Linux/Android.
 */

namespace kvm {

static VfsConfig g_config;
static std::mutex g_mutex;
static std::atomic<bool> g_hooksInstalled{false};

// ─── Path translation ─────────────────────────────────────────────────────────

std::string vfsTranslatePath(const std::string& path) {
    if (path.empty() || g_config.guestPackage.empty()) return path;

    auto redirect = [&](const std::string& prefix) -> std::string {
        if (path.rfind(prefix, 0) == 0) {
            std::string rel = path.substr(prefix.size());
            return g_config.virtualDataRoot
                 + "/data/user/"
                 + g_config.guestUserId
                 + "/"
                 + g_config.guestPackage
                 + rel;
        }
        return {};
    };

    std::string r;
    r = redirect("/data/data/" + g_config.guestPackage);
    if (!r.empty()) return r;
    r = redirect("/data/user/0/" + g_config.guestPackage);
    if (!r.empty()) return r;
    r = redirect("/data/user/" + g_config.guestUserId + "/" + g_config.guestPackage);
    if (!r.empty()) return r;
    return path;
}

// ─── Hook originals (null = hooks removed, use syscall fallback) ──────────────

static int     (*orig_open)(const char*, int, ...)         = nullptr;
static int     (*orig_openat)(int, const char*, int, ...)  = nullptr;
static int     (*orig_access)(const char*, int)            = nullptr;
static FILE*   (*orig_fopen)(const char*, const char*)     = nullptr;
static int     (*orig_stat)(const char*, struct stat*)     = nullptr;
static int     (*orig_mkdir)(const char*, mode_t)          = nullptr;
static int     (*orig_rename)(const char*, const char*)    = nullptr;
static int     (*orig_unlink)(const char*)                 = nullptr;
static ssize_t (*orig_readlink)(const char*, char*, size_t) = nullptr;

// ─── Hook implementations ─────────────────────────────────────────────────────
//
// IMPORTANT: every hook must handle orig_* == nullptr by falling back to the
// raw kernel syscall.  This is required because vfsRemoveHooks() nulls orig_*
// without restoring GOT entries (see file-level comment for rationale).
// Returning -1 unconditionally when orig_* is null would break ALL file I/O
// in the process after un-hook.

static int hook_open(const char* path, int flags, ...) {
    mode_t mode = 0;
    if (flags & O_CREAT) {
        va_list ap;
        va_start(ap, flags);
        mode = static_cast<mode_t>(va_arg(ap, int));
        va_end(ap);
    }
    const char* translated = path;
    std::string t;
    if (path) { t = vfsTranslatePath(path); translated = t.c_str(); }

    if (orig_open)  return orig_open(translated, flags, mode);
    // Syscall fallback — functionally identical to glibc open()
    return static_cast<int>(::syscall(__NR_openat, AT_FDCWD, translated, flags, mode));
}

static int hook_openat(int dirfd, const char* path, int flags, ...) {
    mode_t mode = 0;
    if (flags & O_CREAT) {
        va_list ap;
        va_start(ap, flags);
        mode = static_cast<mode_t>(va_arg(ap, int));
        va_end(ap);
    }
    const char* translated = path;
    std::string t;
    if (path) { t = vfsTranslatePath(path); translated = t.c_str(); }

    if (orig_openat) return orig_openat(dirfd, translated, flags, mode);
    // Syscall fallback
    return static_cast<int>(::syscall(__NR_openat, dirfd, translated, flags, mode));
}

static int hook_access(const char* path, int amode) {
    const char* translated = path;
    std::string t;
    if (path) { t = vfsTranslatePath(path); translated = t.c_str(); }

    if (orig_access) return orig_access(translated, amode);
    // faccessat syscall fallback
#ifdef __NR_faccessat
    return static_cast<int>(::syscall(__NR_faccessat, AT_FDCWD, translated, amode, 0));
#else
    return static_cast<int>(::syscall(__NR_faccessat2, AT_FDCWD, translated, amode, 0));
#endif
}

static FILE* hook_fopen(const char* path, const char* mode) {
    const char* translated = path;
    std::string t;
    if (path) { t = vfsTranslatePath(path); translated = t.c_str(); }

    if (orig_fopen) return orig_fopen(translated, mode);

    // Syscall fallback: convert mode string → flags, then open + fdopen.
    int flags = O_RDONLY;
    bool write = mode && strchr(mode, 'w');
    bool append = mode && strchr(mode, 'a');
    bool plus   = mode && strchr(mode, '+');
    if (write)        flags = plus ? O_RDWR | O_CREAT | O_TRUNC  : O_WRONLY | O_CREAT | O_TRUNC;
    else if (append)  flags = plus ? O_RDWR | O_CREAT | O_APPEND : O_WRONLY | O_CREAT | O_APPEND;
    else if (plus)    flags = O_RDWR;

    int fd = static_cast<int>(::syscall(__NR_openat, AT_FDCWD, translated, flags,
                                        static_cast<mode_t>(0666)));
    if (fd < 0) return nullptr;
    FILE* f = fdopen(fd, mode ? mode : "r");
    if (!f) ::close(fd);
    return f;
}

static int hook_stat(const char* path, struct stat* buf) {
    const char* translated = path;
    std::string t;
    if (path) { t = vfsTranslatePath(path); translated = t.c_str(); }

    if (orig_stat) return orig_stat(translated, buf);
    // fstatat syscall fallback — ARM64 uses __NR_newfstatat (79); __NR_fstatat is
    // not defined in the NDK sysroot for aarch64-linux-android.
    return static_cast<int>(::syscall(__NR_newfstatat, AT_FDCWD, translated, buf, 0));
}

static int hook_mkdir(const char* path, mode_t mode) {
    const char* translated = path;
    std::string t;
    if (path) { t = vfsTranslatePath(path); translated = t.c_str(); }

    if (orig_mkdir) return orig_mkdir(translated, mode);
    return static_cast<int>(::syscall(__NR_mkdirat, AT_FDCWD, translated, mode));
}

static int hook_rename(const char* src, const char* dst) {
    std::string tsrc, tdst;
    if (src) tsrc = vfsTranslatePath(src);
    if (dst) tdst = vfsTranslatePath(dst);
    const char* ps = src ? tsrc.c_str() : src;
    const char* pd = dst ? tdst.c_str() : dst;

    if (orig_rename) return orig_rename(ps, pd);
    return static_cast<int>(::syscall(__NR_renameat, AT_FDCWD, ps, AT_FDCWD, pd));
}

static int hook_unlink(const char* path) {
    const char* translated = path;
    std::string t;
    if (path) { t = vfsTranslatePath(path); translated = t.c_str(); }

    if (orig_unlink) return orig_unlink(translated);
    return static_cast<int>(::syscall(__NR_unlinkat, AT_FDCWD, translated, 0));
}

static ssize_t hook_readlink(const char* path, char* buf, size_t bufsiz) {
    const char* translated = path;
    std::string t;
    if (path) { t = vfsTranslatePath(path); translated = t.c_str(); }

    if (orig_readlink) return orig_readlink(translated, buf, bufsiz);
    return ::syscall(__NR_readlinkat, AT_FDCWD, translated, buf, bufsiz);
}

// ─── Install / Remove ─────────────────────────────────────────────────────────

bool vfsInstallHooks(const VfsConfig& cfg) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_config = cfg;

    if (g_hooksInstalled.load()) {
        KLOGI("VfsNative: config updated (hooks already installed)");
        return true;
    }

    const char* libc = "libc.so";

    struct HookSpec {
        const char* name;
        void*       hookFn;
        void**      origPtr;
    } hooks[] = {
        { "open",     reinterpret_cast<void*>(hook_open),     reinterpret_cast<void**>(&orig_open)     },
        { "openat",   reinterpret_cast<void*>(hook_openat),   reinterpret_cast<void**>(&orig_openat)   },
        { "access",   reinterpret_cast<void*>(hook_access),   reinterpret_cast<void**>(&orig_access)   },
        { "fopen",    reinterpret_cast<void*>(hook_fopen),    reinterpret_cast<void**>(&orig_fopen)    },
        { "stat",     reinterpret_cast<void*>(hook_stat),     reinterpret_cast<void**>(&orig_stat)     },
        { "mkdir",    reinterpret_cast<void*>(hook_mkdir),    reinterpret_cast<void**>(&orig_mkdir)    },
        { "rename",   reinterpret_cast<void*>(hook_rename),   reinterpret_cast<void**>(&orig_rename)   },
        { "unlink",   reinterpret_cast<void*>(hook_unlink),   reinterpret_cast<void**>(&orig_unlink)   },
        { "readlink", reinterpret_cast<void*>(hook_readlink), reinterpret_cast<void**>(&orig_readlink) },
    };

    bool anyOk = false;
    for (auto& h : hooks) {
        void* origOut = nullptr;
        HookStatus s = hookPlt(libc, h.name, h.hookFn, &origOut);
        if (s == HookStatus::OK) {
            *h.origPtr = origOut;
            anyOk = true;
            KLOGI("VfsNative: hooked libc::%s (orig=%p)", h.name, origOut);
        } else {
            KLOGW("VfsNative: failed to hook libc::%s (status=%d) — syscall fallback active",
                  h.name, static_cast<int>(s));
            // Leave orig_* as nullptr.  Hook function will use syscall fallback.
        }
    }

    g_hooksInstalled.store(anyOk);
    return anyOk;
}

void vfsRemoveHooks() {
    std::lock_guard<std::mutex> lock(g_mutex);

    // Null the orig_* pointers.  The hook functions remain installed in GOT
    // (see file-level comment for rationale).  Every hook function has a
    // syscall fallback that activates when orig_* is null, so the process
    // continues to function correctly after un-hook without restoring GOT.
    orig_open     = nullptr;
    orig_openat   = nullptr;
    orig_access   = nullptr;
    orig_fopen    = nullptr;
    orig_stat     = nullptr;
    orig_mkdir    = nullptr;
    orig_rename   = nullptr;
    orig_unlink   = nullptr;
    orig_readlink = nullptr;

    // Clear guest package config so path translation returns the input unchanged.
    g_config = VfsConfig{};

    g_hooksInstalled.store(false);
    KLOGI("VfsNative: hooks deactivated (syscall fallback active)");
}

} // namespace kvm
