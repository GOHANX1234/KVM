#include "memory_utils.h"
#include <cstdio>
#include <cstdlib>
#include <link.h>

namespace kvm {

// ─────────────────────────────────────────────────────────────────────────────
// parseMaps — read /proc/self/maps and build a list of MapEntry structs
// ─────────────────────────────────────────────────────────────────────────────
std::vector<MapEntry> parseMaps() {
    std::vector<MapEntry> entries;
    FILE* fp = fopen("/proc/self/maps", "r");
    if (!fp) {
        KLOGE("parseMaps: cannot open /proc/self/maps: %s", strerror(errno));
        return entries;
    }

    char line[512];
    while (fgets(line, sizeof(line), fp)) {
        MapEntry e{};
        char perms[8]{};
        char pathname[256]{};
        uint32_t devMajor = 0, devMinor = 0;

        // Format: start-end perms offset dev inode pathname
        int matched = sscanf(line,
            "%lx-%lx %7s %lx %x:%x %lu %255s",
            &e.start, &e.end, perms,
            &e.offset, &devMajor, &devMinor, &e.inode, pathname
        );

        if (matched < 7) continue;

        e.readable   = (perms[0] == 'r');
        e.writable   = (perms[1] == 'w');
        e.executable = (perms[2] == 'x');
        e.shared     = (perms[3] == 's');
        e.devMajor   = devMajor;
        e.devMinor   = devMinor;
        if (matched == 8) {
            e.pathname = pathname;
        }
        entries.push_back(std::move(e));
    }
    fclose(fp);
    return entries;
}

// ─────────────────────────────────────────────────────────────────────────────
// findLoadBase — search maps for a .so whose pathname contains `soname`
// ─────────────────────────────────────────────────────────────────────────────
uintptr_t findLoadBase(const char* soname) {
    auto maps = parseMaps();
    for (const auto& e : maps) {
        if (!e.pathname.empty() && e.pathname.find(soname) != std::string::npos) {
            // The first executable segment with offset 0 is the load base.
            if (e.executable && e.offset == 0) {
                return e.start;
            }
        }
    }
    return 0;
}

// ─────────────────────────────────────────────────────────────────────────────
// findSymbol — locate exported symbol; falls back to ELF walking if dlsym fails
// ─────────────────────────────────────────────────────────────────────────────
void* findSymbol(const char* soname, const char* symbol, bool useDlsym) {
    if (useDlsym) {
        void* handle = dlopen(soname, RTLD_NOW | RTLD_NOLOAD);
        if (handle) {
            void* sym = dlsym(handle, symbol);
            dlclose(handle);
            if (sym) return sym;
        }
    }

    // ELF walk fallback: parse the dynamic symbol table in memory.
    uintptr_t base = findLoadBase(soname);
    if (!base) return nullptr;

    const auto* ehdr = reinterpret_cast<const Elf_Ehdr*>(base);
    if (ehdr->e_ident[0] != 0x7f ||
        ehdr->e_ident[1] != 'E'  ||
        ehdr->e_ident[2] != 'L'  ||
        ehdr->e_ident[3] != 'F') {
        KLOGE("findSymbol: invalid ELF magic at base 0x%lx", base);
        return nullptr;
    }

    const Elf_Phdr* phdrs = reinterpret_cast<const Elf_Phdr*>(base + ehdr->e_phoff);
    uintptr_t dynAddr = 0;

    for (int i = 0; i < ehdr->e_phnum; ++i) {
        if (phdrs[i].p_type == PT_DYNAMIC) {
            dynAddr = base + phdrs[i].p_vaddr;
            break;
        }
    }
    if (!dynAddr) return nullptr;

    const Elf_Sym*  symTab = nullptr;
    const char*     strTab = nullptr;

    for (const Elf_Dyn* dyn = reinterpret_cast<const Elf_Dyn*>(dynAddr);
         dyn->d_tag != DT_NULL; ++dyn) {
        switch (dyn->d_tag) {
            case DT_SYMTAB:  symTab   = reinterpret_cast<const Elf_Sym*>(base + dyn->d_un.d_ptr); break;
            case DT_STRTAB:  strTab   = reinterpret_cast<const char*>(base + dyn->d_un.d_ptr);    break;
            case DT_SYMENT:  /* entry size, not count */                                            break;
            case DT_GNU_HASH: {
                // Derive symbol count from GNU hash table
                const uint32_t* gnu = reinterpret_cast<const uint32_t*>(base + dyn->d_un.d_ptr);
                uint32_t nbuckets = gnu[0];
                uint32_t symoffset = gnu[1];
                (void)nbuckets; (void)symoffset;
                // We'll iterate until we hit an address outside valid range below
                break;
            }
            default: break;
        }
    }

    if (!symTab || !strTab) return nullptr;

    // Walk symbol table. We cap at a reasonable limit to avoid reading garbage.
    for (size_t i = 0; i < 65536; ++i) {
        const Elf_Sym& sym = symTab[i];
        if (sym.st_name == 0) continue;
        if (sym.st_value == 0) continue;
        const char* name = strTab + sym.st_name;
        if (strcmp(name, symbol) == 0) {
            return reinterpret_cast<void*>(base + sym.st_value);
        }
    }

    return nullptr;
}

// ─────────────────────────────────────────────────────────────────────────────
bool isAddressMapped(uintptr_t addr) {
    auto maps = parseMaps();
    for (const auto& e : maps) {
        if (addr >= e.start && addr < e.end) return true;
    }
    return false;
}

} // namespace kvm
