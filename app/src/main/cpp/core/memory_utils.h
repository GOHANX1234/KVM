#pragma once

#include "common.h"
#include <vector>
#include <optional>

namespace kvm {

/// Represents a single memory-mapped region from /proc/self/maps.
struct MapEntry {
    uintptr_t   start;
    uintptr_t   end;
    bool        readable;
    bool        writable;
    bool        executable;
    bool        shared;
    uint64_t    offset;
    uint32_t    devMajor;
    uint32_t    devMinor;
    uint64_t    inode;
    std::string pathname;
};

/// Parse /proc/self/maps and return all entries.
std::vector<MapEntry> parseMaps();

/// Find the load base address of a loaded shared library by its soname.
/// Returns 0 if not found.
uintptr_t findLoadBase(const char* soname);

/// Find the in-memory address of a symbol exported from a given SO.
/// Uses dlopen+dlsym if 'useDlsym' is true, otherwise walks ELF tables.
void* findSymbol(const char* soname, const char* symbol, bool useDlsym = true);

/// Check whether a given address falls inside a mapped region.
bool isAddressMapped(uintptr_t addr);

} // namespace kvm
