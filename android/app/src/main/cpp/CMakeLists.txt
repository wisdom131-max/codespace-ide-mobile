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
