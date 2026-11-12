package com.codespace.ide.agent.packages

import org.json.JSONObject
import java.io.File

/**
 * Lockfile inspection — the file as it exists, never by running a package manager.
 *
 * AUDIT (2026-11-12): the app has no package-manager integration and no lockfile parser; the only
 * dependency-shaped code is the terminal's `install_package`, which shells out and is not a parser.
 * So this is new, and it is deliberately PURE: text in, records out, no process, no network, no
 * writes. `package.json`/`pyproject.toml` are never read or touched, and nothing here can change a
 * resolved version.
 *
 * Supported and tested: package-lock.json and npm-shrinkwrap.json (v1, v2, v3), yarn.lock (classic
 * and berry), pnpm-lock.yaml (v5/v6/v9 shapes), poetry.lock, Pipfile.lock, uv.lock, pdm.lock and
 * requirements.txt. A format is only claimed in the docs because a test parses it.
 */
object Lockfiles {

    enum class Manager(val label: String) {
        NPM("npm"), YARN("yarn"), PNPM("pnpm"), POETRY("poetry"), PIPFILE("pipenv"),
        UV("uv"), PDM("pdm"), PIP("pip"),
    }

    /**
     * One resolved dependency. [direct] is null when the format does not say (poetry.lock and
     * requirements.txt cannot tell a direct dependency from a transitive one) — that unknown is
     * reported as unknown rather than guessed.
     */
    data class Pkg(
        val name: String,
        val version: String,
        val direct: Boolean?,
        val dev: Boolean = false,
        val optional: Boolean = false,
        val source: String? = null,
        val integrity: String? = null,
        val markers: String? = null,
    )

    data class Parsed(
        val format: String,
        val manager: Manager,
        val lockfileVersion: String?,
        val packages: List<Pkg>,
        val problems: List<String> = emptyList(),
    ) {
        fun duplicates(): Map<String, List<String>> =
            packages.groupBy { it.name }.filterValues { it.size > 1 }
                .mapValues { e -> e.value.map { it.version }.distinct() }
                .filterValues { it.size > 1 }
    }

    /** Which parser owns this filename, or null. */
    fun managerFor(fileName: String): Manager? = when (fileName.lowercase()) {
        "package-lock.json", "npm-shrinkwrap.json" -> Manager.NPM
        "yarn.lock" -> Manager.YARN
        "pnpm-lock.yaml" -> Manager.PNPM
        "poetry.lock" -> Manager.POETRY
        "pipfile.lock" -> Manager.PIPFILE
        "uv.lock" -> Manager.UV
        "pdm.lock" -> Manager.PDM
        "requirements.txt" -> Manager.PIP
        else -> null
    }

    val supportedNames: List<String> = listOf(
        "package-lock.json", "npm-shrinkwrap.json", "yarn.lock", "pnpm-lock.yaml",
        "poetry.lock", "Pipfile.lock", "uv.lock", "pdm.lock", "requirements.txt",
    )

    fun parse(fileName: String, text: String): Parsed {
        val manager = managerFor(fileName)
            ?: return Parsed(fileName, Manager.NPM, null, emptyList(),
                listOf("'$fileName' is not a lockfile this tool parses. Supported: " + supportedNames.joinToString(", ")))
        val body = text.trim()
        if (body.isEmpty()) return Parsed(fileName, manager, null, emptyList(), listOf("The lockfile is empty."))
        return when (manager) {
            Manager.NPM -> parseNpmPackageLock(fileName, body)
            Manager.YARN -> parseYarn(body)
            Manager.PNPM -> parsePnpm(body)
            Manager.POETRY -> parseTomlPackages(body, manager, "poetry.lock")
            Manager.UV -> parseTomlPackages(body, manager, "uv.lock")
            Manager.PDM -> parseTomlPackages(body, manager, "pdm.lock")
            Manager.PIPFILE -> parsePipfileLock(body)
            Manager.PIP -> parseRequirements(body)
        }
    }

    /** Which supported lockfiles exist in [root], newest-looking first. Bounded, no recursion. */
    fun detect(root: File): List<File> = try {
        val found = ArrayList<File>()
        root.listFiles()?.sortedBy { it.name }?.forEach { f ->
            if (f.isFile && managerFor(f.name) != null) found.add(f)
        }
        // One level down covers the common monorepo layout without walking a whole tree.
        root.listFiles()?.filter { it.isDirectory && it.name != "node_modules" && it.name != ".git" }
            ?.sortedBy { it.name }?.take(20)?.forEach { dir ->
                dir.listFiles()?.sortedBy { it.name }?.forEach { f ->
                    if (f.isFile && managerFor(f.name) != null && found.size < 12) found.add(f)
                }
            }
        found.distinctBy { it.absolutePath }.take(12)
    } catch (e: Exception) {
        emptyList()
    }

    // ── npm: package-lock.json / npm-shrinkwrap.json ──────────────────────────

    private fun parseNpmPackageLock(fileName: String, body: String): Parsed {
        val doc = try { JSONObject(body) } catch (e: Exception) {
            return Parsed(fileName, Manager.NPM, null, emptyList(),
                listOf("Not valid JSON — the lockfile looks corrupt or truncated."))
        }
        val lockVersion = doc.optInt("lockfileVersion", 0).let { if (it > 0) it.toString() else null }
        val out = ArrayList<Pkg>()
        val problems = ArrayList<String>()
        val packages = doc.optJSONObject("packages")
        if (packages != null) {
            val directNames = directFromRootEntry(packages)
            for (key in packages.keys()) {
                if (key.isEmpty()) continue
                val rec = packages.optJSONObject(key) ?: continue
                val name = entryNameFor(key) ?: continue
                val version = rec.optString("version").takeIf { it.isNotBlank() } ?: continue
                if (rec.optBoolean("link") || rec.has("link")) continue
                out.add(
                    Pkg(
                        name = name,
                        version = version,
                        direct = directNames?.contains(name) ?: isTopLevel(key),
                        dev = rec.optBoolean("dev"),
                        optional = rec.optBoolean("optional"),
                        source = rec.optString("resolved").takeIf { it.isNotBlank() },
                        integrity = rec.optString("integrity").takeIf { it.isNotBlank() },
                    ),
                )
            }
        } else {
            val deps = doc.optJSONObject("dependencies")
            if (deps == null) {
                problems.add("No 'packages' or 'dependencies' map — nothing to read.")
            } else {
                walkV1(deps, direct = true, out = out)
            }
        }
        if (out.isEmpty() && problems.isEmpty()) problems.add("The lockfile declares no dependencies.")
        return Parsed(fileName, Manager.NPM, lockVersion, out, problems)
    }

    private fun directFromRootEntry(packages: JSONObject): Set<String>? {
        val root = packages.optJSONObject("") ?: return null
        val names = LinkedHashSet<String>()
        listOf("dependencies", "devDependencies", "optionalDependencies", "peerDependencies").forEach { key ->
            packages.optJSONObject("")?.optJSONObject(key)?.let { m -> m.keys().forEach { names.add(it) } }
        }
        if (names.isEmpty()) {
            root.optJSONObject("dependencies")?.let { m -> m.keys().forEach { names.add(it) } }
        }
        return if (names.isEmpty()) null else names
    }

    private fun entryNameFor(key: String): String? {
        val marker = "node_modules/"
        val idx = key.lastIndexOf(marker)
        if (idx < 0) return key.takeIf { it.isNotBlank() }
        return key.substring(idx + marker.length).takeIf { it.isNotBlank() }
    }

    private fun isTopLevel(key: String): Boolean =
        Regex("^node_modules/[^/]+$").matches(key)

    private fun walkV1(deps: JSONObject, direct: Boolean, out: MutableList<Pkg>) {
        for (name in deps.keys()) {
            val rec = deps.optJSONObject(name) ?: continue
            val version = rec.optString("version").takeIf { it.isNotBlank() }
            if (version != null) {
                out.add(
                    Pkg(
                        name = name, version = version, direct = direct,
                        dev = rec.optBoolean("dev"), optional = rec.optBoolean("optional"),
                        source = rec.optString("resolved").takeIf { it.isNotBlank() },
                        integrity = rec.optString("integrity").takeIf { it.isNotBlank() },
                    ),
                )
            }
            // v1 nests transitive deps under "dependencies" with the same shape.
            rec.optJSONObject("dependencies")?.let { walkV1(it, direct = false, out = out) }
        }
    }

    // ── yarn (classic and berry) ─────────────────────────────────────────────

    private fun parseYarn(body: String): Parsed {
        val out = ArrayList<Pkg>()
        val problems = ArrayList<String>()
        var berry = false
        var name: String? = null
        var version: String? = null
        var resolved: String? = null
        var integrity: String? = null

        fun flush() {
            val n = name; val v = version
            if (n != null && v != null) {
                out.add(Pkg(n, v, direct = null, source = resolved, integrity = integrity))
            }
            name = null; version = null; resolved = null; integrity = null
        }

        for (raw in body.lineSequence()) {
            val line = raw.trimEnd()
            if (line.isBlank() || line.trimStart().startsWith("#")) { continue }
            val indented = line.startsWith(" ") || line.startsWith("\t")
            if (!indented && line.endsWith(":") && !line.contains(": ")) {
                flush()
                val header = line.dropLast(1).trim().trim('"')
                // Yarn berry opens with a __metadata block: it has a version key but is not a package.
                if (header.startsWith("__")) { name = null; continue }
                name = header.substringBefore("@", header).ifBlank { null }
                if (header.contains("@npm:")) berry = true
                continue
            }
            val t = line.trim()
            when {
                t.startsWith("version ") -> version = t.removePrefix("version ").trim().trim('"')
                t.startsWith("version:") -> { berry = true; version = t.removePrefix("version:").trim().trim('"') }
                t.startsWith("resolved ") -> resolved = t.removePrefix("resolved ").trim().trim('"')
                t.startsWith("resolution:") -> resolved = t.removePrefix("resolution:").trim().trim('"')
                t.startsWith("integrity ") -> integrity = t.removePrefix("integrity ").trim()
                t.startsWith("checksum:") -> integrity = t.removePrefix("checksum:").trim()
            }
        }
        flush()
        if (out.isEmpty()) problems.add("No entries parsed — the file is not a yarn.lock this parser understands.")
        return Parsed(if (berry) "yarn.lock (berry)" else "yarn.lock (classic)", Manager.YARN, if (berry) "berry" else "1", out, problems)
    }

    // ── pnpm-lock.yaml ──────────────────────────────────────────────────────

    private fun parsePnpm(body: String): Parsed {
        val out = ArrayList<Pkg>()
        val problems = ArrayList<String>()
        var lockVersion: String? = null
        var inPackages = false
        val direct = LinkedHashSet<String>()

        for (raw in body.lineSequence()) {
            if (raw.isBlank()) continue
            val indent = raw.takeWhile { it == ' ' }.length
            val t = raw.trim()
            if (t.startsWith("lockfileVersion:")) {
                lockVersion = t.removePrefix("lockfileVersion:").trim().trim('\'', '"')
                continue
            }
            if (indent == 0) {
                inPackages = t == "packages:" || t == "snapshots:"
                continue
            }
            if (!inPackages) {
                // Direct dependencies live under importers (v6+) or a root dependencies map.
                if (t.startsWith("dependencies:") || t.startsWith("devDependencies:") ||
                    t.startsWith("optionalDependencies:")) continue
                val m = Regex("^([@A-Za-z0-9._/-]+):?\\s*$|^([@A-Za-z0-9._/-]+):\\s+.*$").find(t)
                val candidate = (m?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
                    ?: m?.groupValues?.getOrNull(2)?.takeIf { it.isNotBlank() })
                if (candidate != null && candidate.indexOf("@") <= 0 && indent <= 6) direct.add(candidate)
                continue
            }
            if (!t.endsWith(":")) continue
            val key = t.dropLast(1).trim().trim('\'', '"')
            // Shapes: "/pkg@1.2.3", "pkg@1.2.3", "/@scope/pkg@1.2.3", "pkg@1.2.3(peer@2)"
            val cleaned = key.removePrefix("/").substringBefore("(")
            val at = cleaned.lastIndexOf('@')
            if (at <= 0) continue
            val pkgName = cleaned.substring(0, at)
            val pkgVer = cleaned.substring(at + 1)
            if (pkgName.isBlank() || pkgVer.isBlank()) continue
            out.add(Pkg(pkgName, pkgVer, direct = if (direct.isEmpty()) null else direct.contains(pkgName)))
        }
        if (lockVersion == null) problems.add("No lockfileVersion line — older pnpm format, parsed on a best-effort basis.")
        if (out.isEmpty()) problems.add("No package entries found under 'packages:'.")
        return Parsed("pnpm-lock.yaml", Manager.PNPM, lockVersion, out, problems)
    }

    // ── poetry.lock / uv.lock / pdm.lock (TOML-ish [[package]] blocks) ──────

    private fun parseTomlPackages(body: String, manager: Manager, format: String): Parsed {
        val out = ArrayList<Pkg>()
        val problems = ArrayList<String>()
        var name: String? = null
        var version: String? = null
        var dev = false
        var optional = false
        var markers: String? = null
        var source: String? = null
        var integrity: String? = null
        var inPackage = false

        fun flush() {
            val n = name; val v = version
            if (n != null) {
                if (v != null) out.add(Pkg(n, v, direct = null, dev = dev, optional = optional, source = source,
                    integrity = integrity, markers = markers))
                else problems.add("'$n' has no version in the lockfile.")
            }
            name = null; version = null; dev = false; optional = false; markers = null; source = null; integrity = null
        }

        for (raw in body.lineSequence()) {
            val t = raw.trim()
            if (t.startsWith("[[")) {
                if (t.startsWith("[[package]]")) { flush(); inPackage = true } else if (inPackage) { /* nested table */ }
                continue
            }
            if (t.startsWith("[")) {
                // A nested table like [package.dependencies] ends the scalar fields we read.
                if (t.startsWith("[package.dependencies]") || t.startsWith("[package.extras]")) inPackage = false
                continue
            }
            if (!inPackage || t.isEmpty() || t.startsWith("#")) continue
            val kv = Regex("^([A-Za-z0-9_-]+)\\s*=\\s*(.+)$").find(t) ?: continue
            val key = kv.groupValues[1]
            val value = kv.groupValues[2].trim().trim('"', '\'')
            when (key) {
                "name" -> name = value
                "version" -> version = value
                "optional" -> optional = value.equals("true", true)
                "category" -> dev = value.equals("dev", true)
                "groups" -> dev = value.contains("dev")
                "group" -> dev = value.contains("dev")
                "markers", "python-versions" -> markers = markers ?: value
                "source" -> source = value
                "url" -> source = source ?: value
                "hash" -> integrity = integrity ?: value
            }
        }
        flush()
        if (out.isEmpty() && problems.isEmpty()) problems.add("No [[package]] blocks found.")
        return Parsed(format, manager, null, out, problems)
    }

    // ── Pipfile.lock ────────────────────────────────────────────────────────

    private fun parsePipfileLock(body: String): Parsed {
        val doc = try { JSONObject(body) } catch (e: Exception) {
            return Parsed("Pipfile.lock", Manager.PIPFILE, null, emptyList(),
                listOf("Not valid JSON — the lockfile looks corrupt or truncated."))
        }
        val out = ArrayList<Pkg>()
        fun section(key: String, dev: Boolean) {
            val sec = doc.optJSONObject(key) ?: return
            for (name in sec.keys()) {
                val rec = sec.optJSONObject(name) ?: continue
                val raw = rec.optString("version")
                val ver = raw.removePrefix("==").takeIf { it.isNotBlank() }
                    ?: rec.optString("ref").takeIf { it.isNotBlank() }
                    ?: "(unpinned)"
                val hashes = rec.optJSONArray("hashes")
                out.add(
                    Pkg(
                        name = name, version = ver, direct = true, dev = dev,
                        optional = rec.optBoolean("optional"),
                        source = rec.optString("git").takeIf { it.isNotBlank() }
                            ?: rec.optString("file").takeIf { it.isNotBlank() },
                        integrity = hashes?.optString(0)?.takeIf { it.isNotBlank() },
                        markers = rec.optString("markers").takeIf { it.isNotBlank() },
                    ),
                )
            }
        }
        section("default", false)
        section("develop", true)
        val problems = if (out.isEmpty()) listOf("No 'default' or 'develop' packages declared.") else emptyList()
        val v = doc.optInt("_meta", 0).let { meta -> doc.optJSONObject("_meta")?.optInt("pipfile-spec", 0) ?: 0 }
            .takeIf { it > 0 }?.toString()
        return Parsed("Pipfile.lock", Manager.PIPFILE, v, out, problems)
    }

    // ── requirements.txt (a dependency file, not a lockfile) ────────────────

    private fun parseRequirements(body: String): Parsed {
        val out = ArrayList<Pkg>()
        val problems = ArrayList<String>()
        for (raw in body.lineSequence()) {
            val line = raw.substringBefore(" #").substringBefore("\t#").trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("-")) continue
            val m = Regex("^([A-Za-z0-9][A-Za-z0-9._-]*)\\s*(\\[[^\\]]*])?\\s*(==|>=|<=|~=|!=|>|<)?\\s*([^;\\s]*)?").find(line) ?: continue
            val name = m.groupValues[1]
            val op = m.groupValues[3]
            val ver = m.groupValues[4].ifBlank { null }
            val markers = line.substringAfter(";", "").trim().ifBlank { null }
            val shown = when {
                ver == null -> "(unpinned)"
                op == "==" -> ver
                else -> op + ver
            }
            out.add(Pkg(name, shown, direct = true, markers = markers))
            if (op != "==" && ver != null) problems.add("'$name' is pinned with '$op', not an exact version.")
        }
        if (out.isEmpty()) problems.add("No requirement lines found.")
        return Parsed("requirements.txt", Manager.PIP, null, out, problems)
    }
}
