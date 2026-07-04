#include "hook_engine.h"
#include "memory_utils.h"
#include <cstring>
#include <sys/mman.h>
#include <link.h>

/*
 * PLT/GOT Hook implementation for ARM64 and X86_64.
 *
 * Strategy:
 *   1. Find the target .so in memory via /proc/self/maps.
 *   2. Walk its PT_DYNAMIC segment to find JMPREL / RELA / RELAENT tables.
 *   3. Locate the GOT slot for the target symbol.
 *   4. mprotect the page PROT_WRITE, overwrite with hook address, restore.
 *
 * This is the same approach used by xHook and bhook.
 *
 * SELinux W^X fix (Android 10+):
 *   Android 10+ enforces W^X (write XOR execute) via SELinux execmem policy.
 *   The old trampoline pool used mmap(PROT_READ|PROT_WRITE|PROT_EXEC) which is
 *   now blocked.  The fix uses a two-phase approach: allocate with PROT_WRITE,
 *   write the trampoline bytes, then re-protect to PROT_EXEC only.
 */

namespace kvm {

// ─── Internal helpers ────────────────────────────────────────────────────────

struct ElfInfo {
    uintptr_t   base;
    const char* strtab;
    const Elf_Sym* symtab;
    const Elf_Rela* jmprel;   ///< JMPREL / PLT relocation table
    size_t      jmprelSz;
    size_t      relaEntSz;
};

static bool loadElfInfo(const char* soname, ElfInfo& out) {
    out.base = findLoadBase(soname);
    if (!out.base) {
        KLOGE("hookPlt: SO not found in maps: %s", soname);
        return false;
    }

    const auto* ehdr = reinterpret_cast<const Elf_Ehdr*>(out.base);
    const auto* phdrs = reinterpret_cast<const Elf_Phdr*>(out.base + ehdr->e_phoff);

    uintptr_t dynAddr = 0;
    for (int i = 0; i < ehdr->e_phnum; ++i) {
        if (phdrs[i].p_type == PT_DYNAMIC) {
            dynAddr = out.base + phdrs[i].p_vaddr;
            break;
        }
    }
    if (!dynAddr) return false;

    out.strtab     = nullptr;
    out.symtab     = nullptr;
    out.jmprel     = nullptr;
    out.jmprelSz   = 0;
    out.relaEntSz  = sizeof(Elf_Rela);

    for (const auto* dyn = reinterpret_cast<const Elf_Dyn*>(dynAddr);
         dyn->d_tag != DT_NULL; ++dyn) {
        switch (dyn->d_tag) {
            case DT_STRTAB:   out.strtab   = reinterpret_cast<const char*>(out.base + dyn->d_un.d_ptr); break;
            case DT_SYMTAB:   out.symtab   = reinterpret_cast<const Elf_Sym*>(out.base + dyn->d_un.d_ptr); break;
            case DT_JMPREL:   out.jmprel   = reinterpret_cast<const Elf_Rela*>(out.base + dyn->d_un.d_ptr); break;
            case DT_PLTRELSZ: out.jmprelSz = static_cast<size_t>(dyn->d_un.d_val); break;
            case DT_RELAENT:  out.relaEntSz = static_cast<size_t>(dyn->d_un.d_val); break;
            default: break;
        }
    }
    return (out.strtab && out.jmprel && out.jmprelSz > 0);
}

// ─── PLT hook ────────────────────────────────────────────────────────────────

HookStatus hookPlt(
        const char* targetSo,
        const char* symbolName,
        void*       hookFunc,
        void**      outOriginal)
{
    if (!targetSo || !symbolName || !hookFunc) return HookStatus::ERR_NULL_TARGET;

    ElfInfo info{};
    if (!loadElfInfo(targetSo, info)) return HookStatus::ERR_SYMBOL_NOT_FOUND;

    size_t entCount = info.jmprelSz / info.relaEntSz;
    for (size_t i = 0; i < entCount; ++i) {
        const Elf_Rela& rela = info.jmprel[i];
        uint32_t symIdx = static_cast<uint32_t>(ELF_R_SYM(rela.r_info));

        if (!info.symtab) continue;
        const Elf_Sym& sym = info.symtab[symIdx];
        if (sym.st_name == 0) continue;

        const char* name = info.strtab + sym.st_name;
        if (strcmp(name, symbolName) != 0) continue;

        // Found the GOT slot.
        void** gotSlot = reinterpret_cast<void**>(info.base + rela.r_offset);

        // Save original before overwriting.
        if (outOriginal) *outOriginal = *gotSlot;

        // Unprotect, patch, reprotect.
        uintptr_t page = reinterpret_cast<uintptr_t>(gotSlot) & ~(getpagesize() - 1UL);
        if (mprotect(reinterpret_cast<void*>(page), getpagesize(),
                     PROT_READ | PROT_WRITE) != 0) {
            KLOGE("hookPlt: mprotect RW failed for %s: %s", symbolName, strerror(errno));
            return HookStatus::ERR_MPROTECT_FAILED;
        }

        *gotSlot = hookFunc;

        // Flush icache (important on ARM)
        __builtin___clear_cache(reinterpret_cast<char*>(gotSlot),
                                reinterpret_cast<char*>(gotSlot + 1));

        if (mprotect(reinterpret_cast<void*>(page), getpagesize(),
                     PROT_READ) != 0) {
            KLOGW("hookPlt: mprotect restore failed for %s (non-fatal)", symbolName);
        }

        KLOGI("hookPlt: hooked %s in %s @ GOT %p → %p", symbolName, targetSo, gotSlot, hookFunc);
        return HookStatus::OK;
    }

    KLOGE("hookPlt: symbol '%s' not found in PLT of '%s'", symbolName, targetSo);
    return HookStatus::ERR_SYMBOL_NOT_FOUND;
}

// ─── Un-hook PLT ─────────────────────────────────────────────────────────────

bool unhookPlt(void* pltEntry, void* originalFunc) {
    if (!pltEntry || !originalFunc) return false;

    void** gotSlot = reinterpret_cast<void**>(pltEntry);
    uintptr_t page = reinterpret_cast<uintptr_t>(gotSlot) & ~(getpagesize() - 1UL);

    if (mprotect(reinterpret_cast<void*>(page), getpagesize(),
                 PROT_READ | PROT_WRITE) != 0) return false;

    *gotSlot = originalFunc;
    __builtin___clear_cache(reinterpret_cast<char*>(gotSlot),
                            reinterpret_cast<char*>(gotSlot + 1));
    mprotect(reinterpret_cast<void*>(page), getpagesize(), PROT_READ);
    return true;
}

// ─── Inline (trampoline) hook — ARM64 ────────────────────────────────────────
//
//  We overwrite the first 16 bytes of targetAddr with:
//    LDR X17, #8          ; load address literal
//    BR  X17              ; branch to hook
//    <8-byte absolute address of hookFunc>
//
//  The 16 saved original bytes + a tail-jump back form the "trampoline" so
//  callers of outOriginal land in the original code after the patched prolog.
//
//  SELinux W^X fix:
//    Old code mapped the trampoline pool PROT_READ|PROT_WRITE|PROT_EXEC which
//    is blocked by Android 10+ SELinux execmem policy.
//    New code uses two mprotect calls:
//      1. PROT_READ|PROT_WRITE  — to write the trampoline bytes
//      2. PROT_READ|PROT_EXEC   — to make it executable (no simultaneous W+X)
//    This satisfies W^X enforcement while still allowing code execution.

#if defined(__aarch64__)

static constexpr size_t TRAMPOLINE_SIZE = 16;

struct Trampoline {
    uint8_t  savedBytes[TRAMPOLINE_SIZE]; ///< original bytes
    void*    target;                      ///< original function start
    uint8_t  jumpBack[TRAMPOLINE_SIZE];   ///< tail-jump to target+TRAMPOLINE_SIZE
    bool     used;
};

// Pool of trampolines — allocated with W^X-safe two-phase protection.
static Trampoline* g_trampolinePool  = nullptr;
static size_t      g_trampolineCount = 0;
static constexpr size_t TRAMPOLINE_POOL_SIZE = 64;

/**
 * Allocate the trampoline pool using a W^X-compatible strategy:
 *   Phase 1: mmap(PROT_READ|PROT_WRITE)  — write the initial zeroed data
 *   Phase 2: mprotect(PROT_READ|PROT_EXEC) — make executable, no longer writable
 *
 * Individual trampolines are written before the pool is made executable.
 * When a new trampoline slot must be written, the page is temporarily
 * re-protected to PROT_WRITE, written, then restored to PROT_EXEC.
 */
static bool ensureTrampolinePool() {
    if (g_trampolinePool) return true;

    size_t poolBytes = TRAMPOLINE_POOL_SIZE * sizeof(Trampoline);

    // Phase 1: allocate writable (no EXEC yet — satisfies W^X)
    void* mem = mmap(nullptr, poolBytes,
                     PROT_READ | PROT_WRITE,
                     MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (mem == MAP_FAILED) {
        KLOGE("hookInline: failed to allocate trampoline pool: %s", strerror(errno));
        return false;
    }

    memset(mem, 0, poolBytes);
    g_trampolinePool  = reinterpret_cast<Trampoline*>(mem);
    g_trampolineCount = TRAMPOLINE_POOL_SIZE;

    // Phase 2: make executable; we will re-protect individual pages when writing.
    if (mprotect(mem, poolBytes, PROT_READ | PROT_EXEC) != 0) {
        KLOGW("hookInline: initial PROT_EXEC failed (%s) — will attempt per-slot", strerror(errno));
        // Not fatal; per-slot mprotect will handle it.
    }

    return true;
}

HookStatus hookInline(void* targetAddr, void* hookFunc, void** outOriginal) {
    if (!targetAddr || !hookFunc) return HookStatus::ERR_NULL_TARGET;

    if (!ensureTrampolinePool()) return HookStatus::ERR_MPROTECT_FAILED;

    // Find free trampoline slot
    Trampoline* tramp = nullptr;
    for (size_t i = 0; i < g_trampolineCount; ++i) {
        if (!g_trampolinePool[i].used) {
            tramp = &g_trampolinePool[i];
            break;
        }
    }
    if (!tramp) {
        KLOGE("hookInline: trampoline pool exhausted");
        return HookStatus::ERR_ALREADY_HOOKED;
    }

    uint8_t* target = reinterpret_cast<uint8_t*>(targetAddr);

    // ── Make the trampoline slot writable (W^X: remove EXEC, add WRITE) ──────
    uintptr_t trampolinePage = reinterpret_cast<uintptr_t>(tramp) & ~(getpagesize() - 1UL);
    if (mprotect(reinterpret_cast<void*>(trampolinePage), getpagesize(),
                 PROT_READ | PROT_WRITE) != 0) {
        KLOGE("hookInline: mprotect RW for trampoline slot failed: %s", strerror(errno));
        return HookStatus::ERR_MPROTECT_FAILED;
    }

    // Save original bytes
    memcpy(tramp->savedBytes, target, TRAMPOLINE_SIZE);
    tramp->target = targetAddr;

    // Build tail-jump (LDR X17, #8 / BR X17 / <addr>) pointing to target+16
    void* returnAddr = target + TRAMPOLINE_SIZE;
    uint32_t ldr_x17 = 0x58000051; // LDR X17, #8  (PC-relative, 2 instructions ahead)
    uint32_t br_x17  = 0xD61F0220; // BR  X17
    memcpy(tramp->jumpBack + 0,  &ldr_x17,    4);
    memcpy(tramp->jumpBack + 4,  &br_x17,     4);
    memcpy(tramp->jumpBack + 8,  &returnAddr, 8);
    tramp->used = true;

    // ── Restore trampoline page to EXEC-only (no WRITE) ─────────────────────
    if (mprotect(reinterpret_cast<void*>(trampolinePage), getpagesize(),
                 PROT_READ | PROT_EXEC) != 0) {
        KLOGW("hookInline: mprotect restore to RX for trampoline failed (non-fatal)");
    }
    __builtin___clear_cache(reinterpret_cast<char*>(tramp),
                            reinterpret_cast<char*>(tramp + 1));

    // ── Patch the target function ─────────────────────────────────────────────
    uintptr_t targetPage = reinterpret_cast<uintptr_t>(target) & ~(getpagesize() - 1UL);
    if (mprotect(reinterpret_cast<void*>(targetPage), getpagesize(),
                 PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
        // Target code page: must remain EXEC after write, so we need RWX briefly.
        // On Android 10+ this may fail with EACCES; log and fall back.
        KLOGE("hookInline: mprotect RWX for target page failed: %s", strerror(errno));
        tramp->used = false;
        return HookStatus::ERR_MPROTECT_FAILED;
    }

    uint32_t ldr_hook = 0x58000051;
    uint32_t br_hook  = 0xD61F0220;
    memcpy(target + 0, &ldr_hook, 4);
    memcpy(target + 4, &br_hook,  4);
    memcpy(target + 8, &hookFunc, 8);

    __builtin___clear_cache(reinterpret_cast<char*>(target),
                            reinterpret_cast<char*>(target + TRAMPOLINE_SIZE));

    mprotect(reinterpret_cast<void*>(targetPage), getpagesize(), PROT_READ | PROT_EXEC);

    if (outOriginal) *outOriginal = tramp->jumpBack;

    KLOGI("hookInline: patched %p → %p, trampoline %p", targetAddr, hookFunc, tramp->jumpBack);
    return HookStatus::OK;
}

#else // x86_64 fallback — not primary target but included for emulator support

HookStatus hookInline(void* targetAddr, void* hookFunc, void** outOriginal) {
    // x86_64: 14-byte absolute indirect JMP: FF 25 00 00 00 00 <8-byte addr>
    static constexpr size_t PATCH_SIZE = 14;

    if (!targetAddr || !hookFunc) return HookStatus::ERR_NULL_TARGET;

    uint8_t* target = reinterpret_cast<uint8_t*>(targetAddr);
    uintptr_t page = reinterpret_cast<uintptr_t>(target) & ~(getpagesize() - 1UL);

    if (mprotect(reinterpret_cast<void*>(page), getpagesize(),
                 PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
        return HookStatus::ERR_MPROTECT_FAILED;
    }

    // Build patch: JMP [RIP+0] + 8-byte absolute address
    uint8_t patch[PATCH_SIZE] = {
        0xFF, 0x25, 0x00, 0x00, 0x00, 0x00,  // JMP QWORD PTR [RIP+0]
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00  // address placeholder
    };
    memcpy(patch + 6, &hookFunc, 8);
    memcpy(target,   patch,     PATCH_SIZE);

    __builtin___clear_cache(reinterpret_cast<char*>(target),
                            reinterpret_cast<char*>(target + PATCH_SIZE));
    mprotect(reinterpret_cast<void*>(page), getpagesize(), PROT_READ | PROT_EXEC);

    // No full trampoline on x86_64 in this implementation — callers must use PLT hooks.
    if (outOriginal) *outOriginal = nullptr;
    return HookStatus::OK;
}

#endif // __aarch64__

} // namespace kvm
