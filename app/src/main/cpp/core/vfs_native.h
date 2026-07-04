#pragma once

#include "common.h"
#include <string>

namespace kvm {

/// Configuration fed from the Java layer before hooks are installed.
struct VfsConfig {
    std::string virtualDataRoot;    ///< /data/data/com.kvm.virtualspace/virtual
    std::string hostPackage;        ///< com.kvm.virtualspace
    // Populated per-virtual-app launch
    std::string guestPackage;       ///< e.g. com.whatsapp
    std::string guestUserId;        ///< e.g. "0", "1" for multi-instance
};

/// Install libc-level hooks for file-system syscalls so that any access by
/// a virtual app to its own data directory is transparently redirected to the
/// isolated virtual storage under the host's private directory.
///
/// Safe to call multiple times — subsequent calls update the config only.
bool vfsInstallHooks(const VfsConfig& cfg);

/// Tear down all VFS hooks (called when a virtual process exits).
void vfsRemoveHooks();

/// Translate a guest path to the host-side redirected path.
/// Returns the original path unchanged if no redirection applies.
std::string vfsTranslatePath(const std::string& guestPath);

} // namespace kvm
