#pragma once

#include "common.h"
#include <functional>

namespace kvm {

/// Result of a hook installation attempt.
enum class HookStatus {
    OK,
    ERR_NULL_TARGET,
    ERR_MPROTECT_FAILED,
    ERR_SYMBOL_NOT_FOUND,
    ERR_ALREADY_HOOKED,
    ERR_UNSUPPORTED_ARCH,
};

/// A single installed hook; keeps the original function pointer so callers
/// can chain to the real implementation.
struct HookEntry {
    const char* symbolName;
    void*       originalFunc;   ///< Points to the trampoline or original site
    void*       hookFunc;       ///< Our replacement
    void*       pltEntry;       ///< Address of the GOT slot that was patched
    bool        active;
};

/// Install a PLT/GOT hook inside `targetSo` for the external symbol `symbolName`,
/// replacing it with `hookFunc`.  The original function pointer is stored in *outOriginal.
HookStatus hookPlt(
    const char* targetSo,
    const char* symbolName,
    void*       hookFunc,
    void**      outOriginal
);

/// Remove a previously installed PLT hook, restoring the original GOT entry.
bool unhookPlt(void* pltEntry, void* originalFunc);

/// Hook a function by absolute address using an inline (trampoline) patch.
/// Writes a near-jump at 'targetAddr' → 'hookFunc'.
/// Stores the saved bytes + original address in a trampoline so the original
/// can still be called.  Returns original-compatible callable in *outOriginal.
HookStatus hookInline(
    void*  targetAddr,
    void*  hookFunc,
    void** outOriginal
);

} // namespace kvm
