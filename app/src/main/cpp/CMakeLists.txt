cmake_minimum_required(VERSION 3.22.1)
project(kvm_native VERSION 1.0.0 LANGUAGES C CXX)

# ── C++17 standard ──────────────────────────────────────────────────────────
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# ── Compiler flags ───────────────────────────────────────────────────────────
add_compile_options(
    -Wall
    -Wno-unused-parameter      # clean NDK interop without noise
    -Wno-unused-variable       # suppress in generated/stub translation units
    -fno-rtti
    -fno-exceptions
    -fstack-protector-strong
    -D_FORTIFY_SOURCE=2
)

# ── Include directories ──────────────────────────────────────────────────────
include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}/include
    ${CMAKE_CURRENT_SOURCE_DIR}/core
)

# ── Source files ─────────────────────────────────────────────────────────────
set(KVM_SOURCES
    # JNI bridge — the single entry point called from Kotlin
    jni_bridge.cpp

    # Hook engine — PLT/GOT patching, inline hooks
    core/hook_engine.cpp

    # Virtual file system — redirects open/stat/access at libc level
    core/vfs_native.cpp

    # Memory utilities — /proc/self/maps parsing, ELF walking
    core/memory_utils.cpp

    # Process info — hide host info from virtual apps
    core/proc_utils.cpp
)

# ── Shared library ───────────────────────────────────────────────────────────
add_library(kvm_native SHARED ${KVM_SOURCES})

# ── Link Android system libs ─────────────────────────────────────────────────
find_library(LOG_LIB   log)
find_library(DL_LIB    dl)
find_library(ANDROID_LIB android)

target_link_libraries(kvm_native
    ${LOG_LIB}
    ${DL_LIB}
    ${ANDROID_LIB}
)

# Strip debug symbols in release
if(CMAKE_BUILD_TYPE STREQUAL "Release")
    add_custom_command(TARGET kvm_native POST_BUILD
        COMMAND ${CMAKE_STRIP} --strip-unneeded
        $<TARGET_FILE:kvm_native>)
endif()
