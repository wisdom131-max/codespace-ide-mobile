cmake_minimum_required(VERSION 3.22.1)
project("ptynative")

add_library(ptynative SHARED pty_native.c)

find_library(log-lib log)
target_link_libraries(ptynative ${log-lib})

add_library(proot SHARED IMPORTED)
set_target_properties(proot PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libproot.so)

add_library(talloc SHARED IMPORTED)
set_target_properties(talloc PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libtalloc.so)

add_library(proot-loader SHARED IMPORTED)
set_target_properties(proot-loader PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libproot-loader.so)

add_library(android-shmem SHARED IMPORTED)
set_target_properties(android-shmem PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libandroid-shmem.so)

# dpkg Android fix — LD_PRELOAD shim so Ubuntu's dpkg works on Android/proot.
# Intercepts link()->rename() (Android blocks hardlinks; dpkg uses link() for its
# status-file backup) and makes chown()/lchown()/fchown() no-ops (EPERM in proot
# without real root). Ported from ubuntu-proot-test after on-device verification
# (nano, gcc toolchain, postgresql all installed cleanly with this shim + the real
# dpkg-split/update-alternatives/service binaries — see AGENTS.md).
add_library(dpkg_android_fix SHARED dpkg_android_fix.c)
# Zero external dependencies: compile freestanding so Ubuntu's glibc ld.so can
# load this via LD_PRELOAD without needing Android's libm/libdl/libc. Uses raw
# aarch64 syscalls instead of libc wrappers.
target_compile_options(dpkg_android_fix PRIVATE -nostdlib -nodefaultlibs -fPIC -ffreestanding)
target_link_options(dpkg_android_fix PRIVATE -nostdlib -nodefaultlibs)

# The NDK's CMake toolchain file auto-appends "-lm" to the final link line via its
# own CMAKE_C_STANDARD_LIBRARIES default, regardless of the flags above (confirmed
# via readelf -d during ubuntu-proot-test's r17/r18 builds — the flags never
# survived to the final link line). Guaranteed fix: strip the NEEDED entry with
# patchelf as a POST_BUILD step; this can't be undone by any toolchain default.
find_program(PATCHELF_EXE patchelf)
if(PATCHELF_EXE)
    add_custom_command(TARGET dpkg_android_fix POST_BUILD
        COMMAND ${PATCHELF_EXE} --remove-needed libm.so $<TARGET_FILE:dpkg_android_fix>
        COMMENT "Stripping NEEDED libm.so from dpkg_android_fix.so (Android NDK toolchain default)"
    )
else()
    message(WARNING "patchelf not found — dpkg_android_fix.so may still have NEEDED libm.so injected by the NDK toolchain")
endif()
