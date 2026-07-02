/*
 * dpkg_android_fix.so — LD_PRELOAD shim for running Ubuntu's unpatched dpkg on Android/proot.
 *
 * COMPILATION: Must be built with -nostdlib -nodefaultlibs so this .so has ZERO
 * external dependencies. When Ubuntu's glibc ld.so loads this via LD_PRELOAD, it
 * must not reference any Android-specific libraries (libm.so, libdl.so, etc.) that
 * don't exist in the Ubuntu rootfs.
 *
 * We use raw Linux syscalls instead of libc wrappers to achieve zero dependencies.
 *
 * Fixes two EPERM failures that Ubuntu's dpkg hits on Android:
 *
 * 1. link(old, new) → EPERM  [lib/dpkg/atomic-file.c]
 *    dpkg uses hardlinks to create status-old backup.
 *    Android blocks hardlinks (EPERM). We redirect to rename(2) syscall.
 *    Termux fix: lib-dpkg-atomic-file.c.patch (#ifdef __ANDROID__ use rename)
 *
 * 2. chown/fchown/lchown → EPERM  [src/main/archives.c]
 *    dpkg tries to set file ownership during unpack.
 *    EPERM inside proot without real root. We return 0 (success, no-op).
 *    Termux fix: src-archives.c.patch (#ifndef __ANDROID__ around chown calls)
 */

/* Raw Linux syscall numbers for aarch64 */
#define __NR_rename    38
#define __NR_renameat  38   /* use rename */
#define __NR_renameat2 276

/* Minimal type definitions — no libc headers (they pull in Android deps) */
typedef unsigned int  uid_t;
typedef unsigned int  gid_t;
typedef int           mode_t;
typedef long          ssize_t;
typedef unsigned long size_t;

/* Raw aarch64 syscall wrapper */
static inline long __syscall2(long n, long a, long b) {
    register long x8 __asm__("x8") = n;
    register long x0 __asm__("x0") = a;
    register long x1 __asm__("x1") = b;
    __asm__ __volatile__("svc #0" : "+r"(x0) : "r"(x8), "r"(x1) : "memory");
    return x0;
}

static inline long __syscall3(long n, long a, long b, long c) {
    register long x8 __asm__("x8") = n;
    register long x0 __asm__("x0") = a;
    register long x1 __asm__("x1") = b;
    register long x2 __asm__("x2") = c;
    __asm__ __volatile__("svc #0" : "+r"(x0) : "r"(x8), "r"(x1), "r"(x2) : "memory");
    return x0;
}

/*
 * link(old, new) → rename(old, new)
 * Android blocks hardlinks; rename achieves the same result for dpkg's status-old backup.
 */
int link(const char *oldpath, const char *newpath) {
    return (int)__syscall2(__NR_rename, (long)oldpath, (long)newpath);
}

/* chown family — all no-ops, return 0 (success) */
int chown(const char *pathname, uid_t owner, gid_t group) {
    (void)pathname; (void)owner; (void)group;
    return 0;
}

int lchown(const char *pathname, uid_t owner, gid_t group) {
    (void)pathname; (void)owner; (void)group;
    return 0;
}

int fchown(int fd, uid_t owner, gid_t group) {
    (void)fd; (void)owner; (void)group;
    return 0;
}
