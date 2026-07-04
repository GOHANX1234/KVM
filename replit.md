# KVM — Virtual Space (Android)

## Project Overview

KVM is a production-quality **Android Virtual Space** app written in **Kotlin + NDK (C/C++17)**.  
It lets users clone installed apps or sideload APKs into a secure, isolated virtual environment where each cloned app gets its own storage, account, and process — without requiring root.

---

## Architecture

```
KVM/
├── app/src/main/
│   ├── cpp/                          ← NDK (C++17)
│   │   ├── CMakeLists.txt
│   │   ├── jni_bridge.cpp            ← JNI entry points (called from KVMNative.kt)
│   │   └── core/
│   │       ├── hook_engine.cpp       ← PLT/GOT + inline hooks (ARM64 + x86_64)
│   │       ├── vfs_native.cpp        ← libc-level file path redirection
│   │       ├── memory_utils.cpp      ← /proc/self/maps parser, ELF symbol finder
│   │       └── proc_utils.cpp        ← process identity masking
│   └── java/com/kvm/
│       ├── KVMApplication.kt         ← Hilt app + engine init
│       ├── core/
│       │   ├── engine/               ← KVMEngine, KVMNative, KVMEngineService
│       │   ├── virtual/              ← VirtualApp, VirtualPackageManager,
│       │   │                           VirtualFileSystem, VirtualActivityManager,
│       │   │                           VirtualProcessManager
│       │   ├── hook/                 ← BinderHookManager, InstrumentationHook,
│       │   │                           VirtualContext
│       │   ├── installer/            ← AppCloner, AppInstaller
│       │   └── loader/               ← VirtualApkLoader, ClassLoaderManager
│       ├── data/                     ← Room DB, DAOs, repositories
│       ├── domain/                   ← Models, use cases
│       ├── presentation/             ← MainActivity, Fragments, ViewModels, Adapters
│       ├── di/                       ← Hilt modules, ServiceLocator
│       └── stub/                     ← 20× StubActivity, 10× StubService, etc.
```

## How the Virtual Space Works (no root)

1. **StubActivity Trick**: 20 placeholder Activity classes are pre-declared in the manifest. When a virtual app starts, AMS validates the stub (it exists), and our `InstrumentationHook.newActivity()` swaps in the real guest Activity class at instantiation time.

2. **Binder IPC Proxy**: `BinderHookManager` uses double-reflection to replace the `IActivityManager` and `IPackageManager` singletons with dynamic Java proxies. Every IPC call from a guest app (startActivity, getPackageInfo, …) goes through our interceptors.

3. **Virtual File System**: Guest app file access to `/data/data/<guestPackage>/…` is transparently redirected to `/data/data/com.kvm.virtualspace/virtual/data/user/<userId>/<guestPackage>/…` via C-level PLT hooks on `open`, `openat`, `stat`, `fopen`, etc.

4. **APK Loading**: `VirtualApkLoader` builds a `DexClassLoader` for the guest APK and patches an `AssetManager` to include the guest APK path, giving it access to its own resources.

5. **VirtualContext**: A `ContextWrapper` subclass that returns the guest package name, data directories, SharedPreferences, and ClassLoader instead of the host's.

## Tech Stack

| Component | Library |
|-----------|---------|
| Language | Kotlin + C++17 |
| DI | Hilt 2.51.1 |
| DB | Room 2.6.1 |
| Async | Coroutines + Flow |
| UI | Material Design 3 |
| Navigation | Navigation Component 2.7.7 |
| Image | Coil 2.7.0 |
| Logging | Timber |
| Build | Gradle 8.7 + Kotlin DSL |
| NDK | CMake 3.22.1, C++17 |

## Build Requirements

- Android Studio Koala (2024.1.1) or newer
- NDK r26+ (installed via SDK Manager → SDK Tools → NDK)
- CMake 3.22.1+
- minSdk 26 (Android 8.0) — `InMemoryDexClassLoader` is available from API 26
- targetSdk 35

## Android Version Compatibility

| API | Notes |
|-----|-------|
| 26–27 | Full support. DexClassLoader-based loading. |
| 28–29 | Hidden API restrictions — bypassed via meta-reflection. |
| 30–31 | Scoped storage active; VFS redirects to private dir. |
| 32–33 | QUERY_ALL_PACKAGES required; declared in manifest. |
| 34–35 | DCL restrictions; APK copied to private storage before loading. |

## Known Limitations (no root)

- SELinux context is inherited from the host; virtual apps cannot access hardware the host cannot.
- Apps with certificate pinning may detect running inside a proxy context.
- Play Integrity / SafetyNet may flag the virtual environment — use only for legitimate multi-instance use.
- Android 14+ DCL restrictions require the APK to reside in app-private storage (already handled).

## User Preferences

- Production-quality code only — no placeholders, no demo data.
- iOS-level UI polish with Material Design 3.
- MVVM + Clean Architecture + Hilt.
- C++17 with full ARM64 support as primary ABI.
