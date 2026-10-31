package com.codespace.ide.data

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

/**
 * Copilot-authored skills (2026-10-31, the last item of the audit: "custom skills authoring").
 *
 * A skill is a named, reusable instruction the copilot writes down once and applies later, the
 * same idea as VS Code's prompt files: a short description that says WHEN to use it, and a body
 * that says WHAT to do. The agent sees the index (name + description) and calls read_skill to pull
 * the body for the job in front of it, so a project can carry its conventions without every one of
 * them riding in every prompt.
 *
 * Skills live in the project at .vncode/skills/<slug>.json so they travel with the repository and
 * are reviewable in git. With no project open they fall back to app storage, which is worse but
 * better than refusing to remember anything.
 *
 * Writes are atomic and read-modify-write never invents state: anything unreadable is reported,
 * never silently overwritten.
 */
object SkillStore {

    data class Skill(
        val slug: String,
        val name: String,
        val description: String,
        val body: String,
    )

    sealed class ListResult {
        data class Ok(val skills: List<Skill>, val root: String) : ListResult()
        data class Unreadable(val reason: String) : ListResult()
    }

    fun skillsDir(context: Context, projectRoot: String?): File {
        val root = projectRoot?.takeIf { it.isNotBlank() }
        return if (root != null) File(root, ".vncode/skills")
        else File(context.filesDir, "skills")
    }

    fun list(context: Context, projectRoot: String?): ListResult {
        val dir = skillsDir(context, projectRoot)
        if (!dir.exists()) return ListResult.Ok(emptyList(), dir.absolutePath)
        if (!dir.isDirectory) return ListResult.Unreadable("${dir.absolutePath} is not a directory")
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?: return ListResult.Unreadable("cannot read ${dir.absolutePath}")
        val skills = ArrayList<Skill>()
        for (f in files.sortedBy { it.name }) {
            val skill = runCatching { parse(f.readText(), f.nameWithoutExtension) }.getOrNull()
                ?: return ListResult.Unreadable("${f.name} is not a readable skill file")
            skills.add(skill)
        }
        return ListResult.Ok(skills, dir.absolutePath)
    }

    /** Creates or replaces one skill. Returns the path written. */
    fun save(context: Context, projectRoot: String?, name: String, description: String, body: String): String {
        val dir = skillsDir(context, projectRoot)
        if (!dir.exists() && !dir.mkdirs()) throw IllegalStateException("cannot create ${dir.absolutePath}")
        val slug = slugOf(name)
        val payload = JSONObject()
            .put("name", name)
            .put("description", description)
            .put("body", body)
            .put("slug", slug)
        val file = File(dir, "$slug.json")
        val atomic = AtomicFile(file)
        val out = atomic.startWrite()
        try {
            out.write(payload.toString(2).toByteArray())
            atomic.finishWrite(out)
        } catch (t: Throwable) {
            atomic.failWrite(out)
            throw t
        }
        return file.absolutePath
    }

    fun delete(context: Context, projectRoot: String?, name: String): Boolean {
        val dir = skillsDir(context, projectRoot)
        val file = File(dir, "${slugOf(name)}.json")
        return file.exists() && file.delete()
    }

    fun read(context: Context, projectRoot: String?, name: String): Skill? {
        val dir = skillsDir(context, projectRoot)
        val file = File(dir, "${slugOf(name)}.json")
        if (!file.exists()) return null
        return runCatching { parse(file.readText(), slugOf(name)) }.getOrNull()
    }

    /** The index the model sees: one line per skill, cheap enough to always include. */
    fun indexBlock(context: Context, projectRoot: String?): String {
        val result = list(context, projectRoot)
        val skills = when (result) {
            is ListResult.Ok -> result.skills
            is ListResult.Unreadable -> return ""
        }
        if (skills.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("PROJECT SKILLS (call read_skill with the name to load one):\n")
        for (s in skills) {
            sb.append("- ").append(s.name)
            if (s.description.isNotBlank()) sb.append(": ").append(s.description)
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun parse(text: String, fallbackSlug: String): Skill {
        val o = JSONObject(text)
        return Skill(
            slug = o.optString("slug", fallbackSlug),
            name = o.optString("name", fallbackSlug),
            description = o.optString("description", ""),
            body = o.optString("body", ""),
        )
    }

    /** "Run the tests" -> "run-the-tests". Stable, so a skill keeps its file across renames of case. */
    fun slugOf(name: String): String {
        val slug = name.lowercase().map { c ->
            when {
                c.isLetterOrDigit() -> c
                else -> '-'
            }
        }.joinToString("")
        return slug.trim('-').replace(Regex("-+"), "-").ifBlank { "skill" }
    }
}
