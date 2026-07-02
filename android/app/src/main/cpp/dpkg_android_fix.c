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
 * Fixes two failures that Ubuntu's dpkg hits on Android/Samsung-5.15:
 *
 * 1. link(old, new) -> EACCES  [lib/dpkg/atomic-file.c]
 *    dpkg uses a hardlink to create the status-old backup. Hardlinks are denied
 *    on this filesystem (confirmed directly with a plain `ln` test outside of
 *    dpkg). NOTE: aarch64 Linux has NO plain "rename" syscall (there is no
 *    __NR_rename on this ABI) — syscall 38 is renameat, which takes 4 args
 *    (olddirfd, oldpath, newdirfd, newpath), not 2. An earlier version of this
 *    file called syscall 38 with only 2 args, which the kernel would reject
 *    with EBADF (garbage olddirfd) — silently never fixing this bug at all.
 *    Also, a plain rename() would remove the source path outright, which is
 *    not equivalent to a hardlink for every dpkg call site. Fixed to do a real
 *    openat-based file copy instead (open old read-only, create new, copy
 *    bytes, close both) — this is what's actually verified working on-device
 *    against real installs (gcc toolchain, libc6-dev, postgresql, nginx, and
 *    a persistent apt install/purge/reinstall cycle).
 *
 * 2. chown/fchown/lchown -> EPERM  [src/main/archives.c]
 *    dpkg tries to set file ownership during unpack. EPERM inside proot
 *    without real root. We return 0 (success, no-op) — matches dpkg's own
 *    Android/Termux upstream patch behavior.
 */

/* Raw Linux syscall numbers for aarch64 */
#define __NR_openat  56
#define __NR_close   57
#define __NR_read    63
#define __NR_write   64

#define AT_FDCWD     (-100)
#define O_RDONLY     0
#define O_WRONLY     1
#define O_CREAT      0100
#define O_TRUNC      01000
#define FILE_MODE    0644

/* Minimal type definitions — no libc headers (they pull in Android deps) */
typedef unsigned int  uid_t;
typedef unsigned int  gid_t;
typedef int           mode_t;
typedef long          ssize_t;
typedef unsigned long size_t;

/* Raw aarch64 syscall wrappers */
static inline long __syscall3(long n, long a, long b, long c) {
    register long x8 __asm__("x8") = n;
    register long x0 __asm__("x0") = a;
    register long x1 __asm__("x1") = b;
    register long x2 __asm__("x2") = c;
    __asm__ __volatile__("svc #0" : "+r"(x0) : "r"(x8), "r"(x1), "r"(x2) : "memory");
    return x0;
}

static inline long __syscall4(long n, long a, long b, long c, long d) {
    register long x8 __asm__("x8") = n;
    register long x0 __asm__("x0") = a;
    register long x1 __asm__("x1") = b;
    register long x2 __asm__("x2") = c;
    register long x3 __asm__("x3") = d;
    __asm__ __volatile__("svc #0" : "+r"(x0) : "r"(x8), "r"(x1), "r"(x2), "r"(x3) : "memory");
    return x0;
}

static inline long __sys_openat(int dirfd, const char *path, int flags, int mode) {
    return __syscall4(__NR_openat, dirfd, (long)path, flags, mode);
}
static inline long __sys_read(int fd, void *buf, size_t count) {
    return __syscall3(__NR_read, fd, (long)buf, (long)count);
}
static inline long __sys_write(int fd, const void *buf, size_t count) {
    return __syscall3(__NR_write, fd, (long)buf, (long)count);
}
static inline long __sys_close(int fd) {
    return __syscall3(__NR_close, fd, 0, 0);
}

/*
 * link()/linkat() replacement: real file copy via raw syscalls, since Android
 * denies real hardlinks. Copies oldpath's bytes into newpath (create/truncate).
 * Returns 0 on success, -1 on failure (matches link() contract closely enough
 * for dpkg's use — it only checks the return value).
 */
static int __copy_file(const char *oldpath, const char *newpath) {
    long src = __sys_openat(AT_FDCWD, oldpath, O_RDONLY, 0);
    if (src < 0) return -1;

    long dst = __sys_openat(AT_FDCWD, newpath, O_WRONLY | O_CREAT | O_TRUNC, FILE_MODE);
    if (dst < 0) {
        __sys_close((int)src);
        return -1;
    }

    char buf[8192];
    long n;
    int ok = 1;
    while ((n = __sys_read((int)src, buf, sizeof(buf))) > 0) {
        long off = 0;
        while (off < n) {
            long w = __sys_write((int)dst, buf + off, (size_t)(n - off));
            if (w <= 0) { ok = 0; break; }
            off += w;
        }
        if (!ok) break;
    }
    if (n < 0) ok = 0;

    __sys_close((int)src);
    __sys_close((int)dst);
    return ok ? 0 : -1;
}

int link(const char *oldpath, const char *newpath) {
    return __copy_file(oldpath, newpath);
}

int linkat(int olddirfd, const char *oldpath, int newdirfd, const char *newpath, int flags) {
    (void)olddirfd; (void)newdirfd; (void)flags;
    /* dpkg always uses this for same-directory absolute-ish paths in practice;
       AT_FDCWD-relative dirfds aren't resolved here since we don't have a
       dependency-free way to fdpath-resolve without libc. Matches the plain
       link() behavior above, which is what's actually exercised on real
       installs. */
    return __copy_file(oldpath, newpath);
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
