#pragma once

#include <jni.h>
#include <android/log.h>
#include <string>
#include <cstdint>
#include <cstring>
#include <unistd.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <dirent.h>
#include <errno.h>

// ── Logging ───────────────────────────────────────────────────────────────────
#define KVM_TAG "KVM_Native"

#define KLOGI(...) __android_log_print(ANDROID_LOG_INFO,  KVM_TAG, __VA_ARGS__)
#define KLOGE(...) __android_log_print(ANDROID_LOG_ERROR, KVM_TAG, __VA_ARGS__)
#define KLOGW(...) __android_log_print(ANDROID_LOG_WARN,  KVM_TAG, __VA_ARGS__)
#define KLOGD(...) __android_log_print(ANDROID_LOG_DEBUG, KVM_TAG, __VA_ARGS__)

// ── Utility macros ────────────────────────────────────────────────────────────
#define LIKELY(x)   __builtin_expect(!!(x), 1)
#define UNLIKELY(x) __builtin_expect(!!(x), 0)

#define PAGE_SIZE_ALIGN(x) (((x) + (getpagesize() - 1)) & ~(getpagesize() - 1))

// ── ELF word size ─────────────────────────────────────────────────────────────
#if defined(__LP64__)
    #include <elf.h>
    #include <link.h>
    typedef Elf64_Ehdr  Elf_Ehdr;
    typedef Elf64_Shdr  Elf_Shdr;
    typedef Elf64_Sym   Elf_Sym;
    typedef Elf64_Rel   Elf_Rel;
    typedef Elf64_Rela  Elf_Rela;
    typedef Elf64_Phdr  Elf_Phdr;
    typedef Elf64_Dyn   Elf_Dyn;   ///< FIX: typedef missing, causes compile error on ARM64
    typedef Elf64_Word  Elf_Word;
    typedef Elf64_Addr  Elf_Addr;
    #define ELF_R_SYM(x)  ELF64_R_SYM(x)
    #define ELF_R_TYPE(x) ELF64_R_TYPE(x)
#else
    #include <elf.h>
    #include <link.h>
    typedef Elf32_Ehdr  Elf_Ehdr;
    typedef Elf32_Shdr  Elf_Shdr;
    typedef Elf32_Sym   Elf_Sym;
    typedef Elf32_Rel   Elf_Rel;
    typedef Elf32_Rela  Elf_Rela;
    typedef Elf32_Phdr  Elf_Phdr;
    typedef Elf32_Dyn   Elf_Dyn;   ///< FIX: typedef missing
    typedef Elf32_Word  Elf_Word;
    typedef Elf32_Addr  Elf_Addr;
    #define ELF_R_SYM(x)  ELF32_R_SYM(x)
    #define ELF_R_TYPE(x) ELF32_R_TYPE(x)
#endif

// ── Guard macros ─────────────────────────────────────────────────────────────
#define RETURN_IF_NULL(ptr, ret) \
    do { if (UNLIKELY(!(ptr))) { KLOGE("Null pointer: " #ptr); return (ret); } } while(0)

#define CHECK_JNI_EXCEPTION(env) \
    do { if ((env)->ExceptionCheck()) { (env)->ExceptionDescribe(); (env)->ExceptionClear(); } } while(0)

namespace kvm {

/// Align an address down to page boundary.
inline uintptr_t pageAlign(uintptr_t addr) {
    return addr & ~(static_cast<uintptr_t>(getpagesize()) - 1);
}

/// Change protection of a single page containing 'addr'.
inline bool unprotectPage(void* addr) {
    uintptr_t page = pageAlign(reinterpret_cast<uintptr_t>(addr));
    return mprotect(reinterpret_cast<void*>(page), getpagesize(),
                    PROT_READ | PROT_WRITE | PROT_EXEC) == 0;
}

/// Restore read-only protection for a page.
inline bool reprotectPage(void* addr) {
    uintptr_t page = pageAlign(reinterpret_cast<uintptr_t>(addr));
    return mprotect(reinterpret_cast<void*>(page), getpagesize(),
                    PROT_READ | PROT_EXEC) == 0;
}

} // namespace kvm
