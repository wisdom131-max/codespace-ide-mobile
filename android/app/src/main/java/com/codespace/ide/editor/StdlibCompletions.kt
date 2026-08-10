package com.codespace.ide.editor

import com.codespace.ide.domain.Language

/**
 * C13: Stdlib/builtin completions for languages without an LSP server.
 * Provides Python builtins, Python stdlib modules, and JS/TS globals
 * so typing "math." or "import o" shows useful suggestions.
 */
object StdlibCompletions {

    // Python builtin functions (available in every scope without import)
    private val PYTHON_BUILTINS = listOf(
        "print", "input", "len", "range", "enumerate", "zip", "map", "filter",
        "sorted", "reversed", "sum", "min", "max", "abs", "round", "pow",
        "divmod", "all", "any", "bool", "int", "float", "str", "list",
        "dict", "set", "tuple", "frozenset", "bytes", "bytearray",
        "type", "isinstance", "issubclass", "id", "hash", "dir", "vars",
        "globals", "locals", "exec", "eval", "compile", "open", "chr",
        "ord", "bin", "hex", "oct", "format", "repr", "getattr", "setattr",
        "delattr", "hasattr", "property", "staticmethod", "classmethod",
        "super", "iter", "next", "slice", "object", "help", "breakpoint",
    )

    // Python stdlib modules (for import completions)
    // D3-EXPANSION (2026-08-10): Full Python 3.x standard library module list, not just ~50 common ones.
    // This is a hardcoded FALLBACK for when pylsp/jedi isn't running or hasn't responded yet.
    // NOTE: This will never include third-party pip packages (matplotlib, requests, etc.) —
    // those can only come from the real LSP (jedi introspects actual installed site-packages).
    // See AGENTS.md "Import Completion Parity" section for the full plan.
    private val PYTHON_MODULES = listOf(
        "os", "sys", "math", "json", "pathlib", "datetime", "time", "random",
        "collections", "itertools", "functools", "typing", "re", "string",
        "io", "csv", "sqlite3", "hashlib", "base64", "pickle", "shutil",
        "tempfile", "glob", "argparse", "logging", "subprocess", "threading",
        "multiprocessing", "asyncio", "abc", "copy", "pprint", "traceback",
        "inspect", "unittest", "contextlib", "dataclasses", "enum", "struct",
        "socket", "http", "urllib", "xml", "html", "email", "smtplib",
        "queue", "decimal", "fractions", "statistics", "bisect", "array",
        "weakref", "gc", "atexit", "signal", "mmap", "platform", "uuid",
        "abc", "aifc", "antigravity", "asynchat", "asyncore", "audioop",
        "bdb", "binascii", "bisect", "builtins", "bz2", "cProfile",
        "calendar", "cgi", "cgitb", "chunk", "cmath", "cmd", "code",
        "codecs", "codeop", "colorsys", "compileall", "concurrent",
        "configparser", "contextvars", "copyreg", "crypt", "curses",
        "dbm", "difflib", "dis", "distutils", "doctest", "encodings",
        "ensurepip", "faulthandler", "fcntl", "filecmp", "fileinput",
        "fnmatch", "formatter", "fractions", "ftplib", "getopt", "getpass",
        "gettext", "graphlib", "grp", "gzip", "hmac", "idlelib", "imaplib",
        "imghdr", "imp", "importlib", "keyword", "lib2to3", "linecache",
        "locale", "lzma", "mailbox", "mailcap", "marshal", "mimetypes",
        "mmap", "modulefinder", "msilib", "msvcrt", "netrc", "nis",
        "nntplib", "numbers", "opcode", "operator", "optparse", "os",
        "ossaudiodev", "pdb", "pickletools", "pipes", "pkgutil", "plistlib",
        "poplib", "posix", "posixpath", "profile", "pstats", "pty",
        "pwd", "py_compile", "pyclbr", "pydoc", "quopri", "readline",
        "reprlib", "resource", "rlcompleter", "runpy", "sched", "secrets",
        "select", "selectors", "shelve", "shlex", "site", "smtpd",
        "sndhdr", "spwd", "sqlite3", "sre_compile", "sre_constants",
        "sre_parse", "ssl", "stat", "stringprep", "sunau", "symtable",
        "sysconfig", "syslog", "tabnanny", "tarfile", "telnetlib",
        "termios", "textwrap", "this", "threading", "timeit", "tkinter",
        "token", "tokenize", "tomllib", "trace", "traceback", "tracemalloc",
        "tty", "turtle", "turtledemo", "types", "unicodedata", "urllib",
        "venv", "warnings", "wave", "webbrowser", "winreg", "winsound",
        "wsgiref", "xdrlib", "xmlrpc", "zipapp", "zipfile", "zipimport",
        "zlib", "zoneinfo",
        // Common third-party packages (best-effort — only shown if actually installed;
        // real detection requires jedi/pylsp introspecting the live environment)
        "requests", "numpy", "pandas", "matplotlib", "flask", "django",
        "pytest", "setuptools", "pip", "wheel", "yaml", "attr", "attrs",
        "click", "jinja2", "markupsafe", "certifi", "urllib3", "idna",
        "charset_normalizer", "six", "dateutil", "pytz", "cryptography",
        "cffi", "pycparser", "packaging", "pyparsing", "mock", "coverage",
        "tox", "black", "flake8", "pylint", "mypy", "mypy_extensions",
        "isort", "autopep8", "pycodestyle", "pyflakes", "jedi", "parso",
        "openpyxl", "xlrd", "xlwt", "pillow", "PIL", "scipy", "sklearn",
        "sympy", "networkx", "beautifulsoup4", "bs4", "lxml", "html5lib",
        "aiohttp", "httpx", "websockets", "sqlalchemy", "pymongo", "redis",
        "celery", "gunicorn", "uvicorn", "fastapi", "pydantic", "starlette",
        "boto3", "botocore", "google", "protobuf", "grpc", "oauthlib",
        "opentracing", "objgraph", "odbc", "olefile", "m3u8",
    ).distinct()

    // Python math module members (for "math." completions)
    private val PYTHON_MATH_MEMBERS = listOf(
        "pi", "e", "tau", "inf", "nan", "ceil", "floor", "sqrt", "pow",
        "exp", "log", "log2", "log10", "sin", "cos", "tan", "asin",
        "acos", "atan", "atan2", "sinh", "cosh", "tanh", "degrees",
        "radians", "gcd", "lcm", "isqrt", "isclose", "copysign",
        "fabs", "factorial", "fmod", "modf", "frexp", "ldexp", "remainder",
        "trunc", "erf", "erfc", "gamma", "lgamma", "hypot", "dist",
    )

    // Python os module members
    private val PYTHON_OS_MEMBERS = listOf(
        "getcwd", "chdir", "listdir", "mkdir", "rmdir", "remove", "rename",
        "path", "environ", "getenv", "putenv", "system", "popen", "sep",
        "linesep", "pathsep", "name", "uname", "getpid", "chmod", "stat",
        "walk", "scandir", "fspath", "getlogin", "urandom", "abort",
    )

    // Python os.path members
    private val PYTHON_PATH_MEMBERS = listOf(
        "join", "split", "splitext", "basename", "dirname", "exists",
        "isfile", "isdir", "isabs", "abspath", "relpath", "normpath",
        "expanduser", "expandvars", "commonpath", "samefile", "getsize",
        "getmtime", "getatime", "getctime", "sep", "altsep",
    )

    // Python sys module members
    private val PYTHON_SYS_MEMBERS = listOf(
        "argv", "exit", "path", "modules", "stdin", "stdout", "stderr",
        "platform", "version", "version_info", "executable", "prefix",
        "maxsize", "maxint", "getrecursionlimit", "setrecursionlimit",
        "getsizeof", "getrefcount", "intern", "implementation",
    )

    // Python json module members
    private val PYTHON_JSON_MEMBERS = listOf(
        "load", "loads", "dump", "dumps", "JSONEncoder", "JSONDecoder",
    )

    // JS/TS global objects and functions
    private val JS_GLOBALS = listOf(
        "console", "Math", "JSON", "Object", "Array", "String", "Number",
        "Boolean", "Promise", "Symbol", "Map", "Set", "WeakMap", "WeakSet",
        "Date", "RegExp", "Error", "TypeError", "RangeError", "SyntaxError",
        "ReferenceError", "Intl", "Uint8Array", "Int8Array", "Uint16Array",
        "Int16Array", "Uint32Array", "Int32Array", "Float32Array",
        "Float64Array", "BigInt", "BigInt64Array", "DataView", "ArrayBuffer",
        "encodeURIComponent", "decodeURIComponent", "encodeURI", "decodeURI",
        "parseInt", "parseFloat", "isNaN", "isFinite", "setTimeout",
        "setInterval", "clearTimeout", "clearInterval", "queueMicrotask",
        "structuredClone", "atob", "btoa", "fetch", "Request", "Response",
        "Headers", "URL", "URLSearchParams", "FormData", "Blob", "File",
        "FileReader", "TextDecoder", "TextEncoder", "crypto", "performance",
        "globalThis", "window", "document", "localStorage", "sessionStorage",
        "navigator", "location", "history", "screen",
    )

    // JS Math members
    private val JS_MATH_MEMBERS = listOf(
        "PI", "E", "LN2", "LN10", "LOG2E", "LOG10E", "SQRT2", "SQRT1_2",
        "abs", "ceil", "floor", "round", "trunc", "sign", "sqrt", "cbrt",
        "exp", "log", "log2", "log10", "pow", "min", "max", "random",
        "sin", "cos", "tan", "asin", "acos", "atan", "atan2", "sinh",
        "cosh", "tanh", "hypot", "fround", "clz32", "imul",
    )

    // JS console members
    private val JS_CONSOLE_MEMBERS = listOf(
        "log", "error", "warn", "info", "debug", "trace", "dir", "table",
        "group", "groupEnd", "groupCollapsed", "time", "timeEnd",
        "assert", "count", "clear",
    )

    // JS Object members
    private val JS_OBJECT_MEMBERS = listOf(
        "keys", "values", "entries", "assign", "freeze", "seal",
        "create", "defineProperty", "defineProperties", "getPrototypeOf",
        "setPrototypeOf", "getOwnPropertyNames", "getOwnPropertyDescriptor",
        "getOwnPropertyDescriptors", "fromEntries", "hasOwn", "is",
        "isFrozen", "isSealed", "isExtensible", "preventExtensions",
    )

    // JS Array members
    private val JS_ARRAY_MEMBERS = listOf(
        "push", "pop", "shift", "unshift", "splice", "slice", "concat",
        "join", "reverse", "sort", "indexOf", "lastIndexOf", "includes",
        "find", "findIndex", "filter", "map", "reduce", "reduceRight",
        "forEach", "some", "every", "flat", "flatMap", "fill", "copyWithin",
        "at", "entries", "keys", "values", "toString",
    )

    // JS String members
    private val JS_STRING_MEMBERS = listOf(
        "charAt", "charCodeAt", "codePointAt", "concat", "includes",
        "endsWith", "startsWith", "indexOf", "lastIndexOf", "match",
        "matchAll", "padStart", "padEnd", "repeat", "replace", "replaceAll",
        "search", "slice", "split", "substring", "substr", "toLowerCase",
        "toUpperCase", "trim", "trimStart", "trimEnd", "normalize", "at",
        "fromCharCode", "fromCodePoint",
    )

    // Kotlin stdlib (most common)
    private val KOTLIN_STDLIB = listOf(
        "println", "print", "readLine", "readln", " listOf", "mutableListOf",
        "setOf", "mutableSetOf", "mapOf", "mutableMapOf", "arrayOf",
        "arrayListOf", "sequence", "sequenceOf", "emptyList", "emptySet",
        "emptyMap", "emptyArray", "repeat", "with", "run", "let", "also",
        "apply", "takeIf", "takeUnless", "lazy", "lateinit", "TODO",
        "assert", "require", "check", "error", "measureTimeMillis",
        "Thread", "Runnable", "CoroutineScope", "launch", "async",
        "Dispatchers", "suspendCancellableCoroutine", "delay", "channelFlow",
        "flowOf", "flow", "collect", "emit", "fold", "reduce", "groupBy",
        "partition", "chunked", "windowed", "zip", "zipWithNext", "flatten",
        "flatMap", "sortedBy", "sortedByDescending", "sortedWith", "associate",
        "associateBy", "associateWith", "distinctBy", "shuffled", "sorted",
    )

    /**
     * Returns stdlib completions matching the given prefix for the given language.
     * Also handles dot-qualified member access (e.g. "math." returns math module members).
     */
    fun completionsFor(prefix: String, lang: Language): List<Pair<String, String>> {
        if (prefix.isEmpty()) return emptyList()
        val p = prefix.lowercase()
        val results = mutableListOf<Pair<String, String>>()

        when (lang) {
            Language.PYTHON -> {
                // Dot-triggered member completions
                if (p.contains(".")) {
                    val parts = p.split(".")
                    val module = parts[0]
                    val memberPrefix = if (parts.size > 1) parts.last() else ""
                    val members = when (module) {
                        "math" -> PYTHON_MATH_MEMBERS
                        "os" -> PYTHON_OS_MEMBERS
                        "os.path", "pathlib" -> PYTHON_PATH_MEMBERS
                        "sys" -> PYTHON_SYS_MEMBERS
                        "json" -> PYTHON_JSON_MEMBERS
                        else -> emptyList()
                    }
                    members.filter { memberPrefix.isEmpty() || it.lowercase().startsWith(memberPrefix) }
                        .sorted()
                        .forEach { results.add(it to "$module.$it") }
                    return results.take(10)
                }
                // Regular prefix completions
                PYTHON_BUILTINS.filter { it.lowercase().startsWith(p) }
                    .sorted().forEach { results.add(it to "Python builtin function") }
                PYTHON_MODULES.filter { it.lowercase().startsWith(p) }
                    .sorted().forEach { results.add(it to "Python stdlib module") }
            }
            Language.JAVASCRIPT, Language.TYPESCRIPT -> {
                // Dot-triggered member completions
                if (p.contains(".")) {
                    val parts = p.split(".")
                    val obj = parts[0]
                    val memberPrefix = if (parts.size > 1) parts.last() else ""
                    val members = when (obj) {
                        "math" -> JS_MATH_MEMBERS
                        "console" -> JS_CONSOLE_MEMBERS
                        "object" -> JS_OBJECT_MEMBERS
                        "array" -> JS_ARRAY_MEMBERS
                        "string" -> JS_STRING_MEMBERS
                        else -> emptyList()
                    }
                    members.filter { memberPrefix.isEmpty() || it.lowercase().startsWith(memberPrefix) }
                        .sorted()
                        .forEach { results.add(it to "$obj.$it") }
                    return results.take(10)
                }
                // Regular prefix completions
                JS_GLOBALS.filter { it.lowercase().startsWith(p) }
                    .sorted().forEach { results.add(it to "JS global") }
            }
            Language.KOTLIN -> {
                KOTLIN_STDLIB.filter { it.lowercase().startsWith(p) }
                    .sorted().forEach { results.add(it to "Kotlin stdlib") }
            }
            else -> {}
        }
        return results.take(10)
    }
}
