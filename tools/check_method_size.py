#!/usr/bin/env python3
"""Fail the build when any method approaches the JVM's 65,535-byte method limit.

WHY THIS EXISTS
    CodeEditor reached 65,453 bytes of bytecode - 99.9% of the limit - and the only warning anyone
    got was a hard compile failure at :app:compileProdDebugKotlin. A method that size is a landmine:
    the next few lines added to it break the build with an error that points at the wrong thing.
    :app:compileDevDebugKotlin staying green proves nothing, because the dev variant is not the one
    that tripped.

WHAT IT DOES
    Parses the .class files the prod build just produced, measures every method's Code attribute,
    prints the five largest, and exits non-zero when the largest exceeds THRESHOLD of 65,535 bytes.

USAGE
    python3 tools/check_method_size.py <classes-dir> [--threshold-percent 90]
    The classes dir is a kotlin-classes output such as app/build/tmp/kotlin-classes/prodDebug.

Standard library only. No third-party code is installed or executed.
"""
import struct
import sys
import os

LIMIT = 65535


def _u1(d, i):
    return d[i], i + 1


def _u2(d, i):
    return struct.unpack_from(">H", d, i)[0], i + 2


def _u4(d, i):
    return struct.unpack_from(">I", d, i)[0], i + 4


def parse_class_methods(path):
    """Return [(name, code_length)] for every method with a Code attribute."""
    with open(path, "rb") as fh:
        d = fh.read()
    if len(d) < 10 or d[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError("not a class file")
    count, i = _u2(d, 8)
    n = count - 1  # constant_pool_count counts slots plus one
    pool = []
    while n > 0:
        tag, i = _u1(d, i)
        n -= 1
        if tag == 1:  # Utf8
            ln, i = _u2(d, i)
            pool.append(d[i:i + ln].decode("utf-8", "replace"))
            i += ln
        elif tag in (7, 8, 16, 19, 20):  # Class, String, MethodType, Module, Package
            pool.append(None)
            i += 2
        elif tag == 15:  # MethodHandle
            pool.append(None)
            i += 3
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):  # Int, Float, refs, NameAndType, Dynamic
            pool.append(None)
            i += 4
        elif tag in (5, 6):  # Long, Double take two slots
            pool.extend([None, None])
            i += 8
            n -= 1
        else:
            raise ValueError("unknown constant pool tag %d in %s" % (tag, path))

    # constant-pool indices are 1-based, so slot 0 is a placeholder: without it every name and
    # attribute lookup lands one entry early (which reads as "the largest method is 27 bytes").
    pool.insert(0, None)

    i += 6  # access_flags, this_class, super_class
    ifaces, i = _u2(d, i)
    i += 2 * ifaces

    fields, i = _u2(d, i)
    for _ in range(fields):
        i += 6
        attrs, i = _u2(d, i)
        for _ in range(attrs):
            i += 2
            ln, i = _u4(d, i)
            i += ln

    methods, i = _u2(d, i)
    out = []
    for _ in range(methods):
        # method_info is access_flags, name_index, descriptor_index: THREE u2s, not four.
        _, name_idx, _ = struct.unpack_from(">HHH", d, i)
        i += 6
        name = pool[name_idx] if name_idx < len(pool) else "?"
        if not isinstance(name, str):
            name = "method#" + str(name_idx)
        attrs, i = _u2(d, i)
        code_len = 0
        for _ in range(attrs):
            attr_name_idx, i = _u2(d, i)
            ln, i = _u4(d, i)
            attr_name = pool[attr_name_idx] if attr_name_idx < len(pool) else None
            if attr_name == "Code":
                # The Code attribute's own length is not the method size: it also carries the
                # exception table, LineNumberTable and LocalVariableTable. code_length is the u4
                # that follows max_stack and max_locals inside the attribute, and THAT is the
                # number subject to the 65,535 limit.
                code_len = struct.unpack_from(">I", d, i + 4)[0]
            i += ln
        if code_len:
            out.append((name, code_len))
    return out


def main(argv):
    if not argv:
        print("usage: check_method_size.py <classes-dir> [--threshold-percent N]", file=sys.stderr)
        return 2
    root = argv[0]
    pct = 90.0
    if "--threshold-percent" in argv:
        pct = float(argv[argv.index("--threshold-percent") + 1])
    if not os.path.isdir(root):
        print("classes dir not found: %s" % root, file=sys.stderr)
        return 2

    limit = LIMIT * pct / 100.0
    everything = []
    for dirpath, _, files in os.walk(root):
        for f in files:
            if not f.endswith(".class"):
                continue
            path = os.path.join(dirpath, f)
            try:
                for name, size in parse_class_methods(path):
                    everything.append((size, name, os.path.relpath(path, root)))
            except Exception as exc:  # a class we cannot parse must not hide a bigger one
                print("warn: skipped %s (%s)" % (path, exc), file=sys.stderr)
    if not everything:
        print("no methods measured under %s" % root, file=sys.stderr)
        return 2

    everything.sort(key=lambda x: -x[0])
    print("largest methods (limit %d bytes, failing above %.0f%% = %d bytes):"
          % (LIMIT, pct, int(limit)))
    for size, name, path in everything[:5]:
        print("  %7d bytes  %5.1f%%  %s  %s" % (size, 100.0 * size / LIMIT, path, name))
    worst = everything[0]
    if worst[0] > limit:
        print("FAIL: %s in %s is %d bytes, over the %.0f%% threshold"
              % (worst[1], worst[2], worst[0], pct), file=sys.stderr)
        return 1
    print("OK: largest method is %.1f%% of the limit" % (100.0 * worst[0] / LIMIT))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
