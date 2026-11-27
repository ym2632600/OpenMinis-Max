package com.openminis.app.share

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.openminis.app.MinisApp
import com.openminis.app.data.repository.SkillRepository
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * C11: agent sharing with a friend.
 *
 * Export: packs the user's enabled, non-bundled skills (full SKILL.md
 * content) plus the curated GLOBAL.md into one JSON file shared through the
 * Android share sheet (link or QR is up to the receiving app).
 *
 * Import: ShareReceiverActivity detects the bundle, shows the receiver a
 * checklist of every included item, and installs ONLY what the receiver
 * leaves checked - the receiver approves every included skill.
 */
object AgentShareBundle {

    const val MAGIC = "openminis-agent-export"
    const val VERSION = 1

    /** Pseudo-skill id used for the GLOBAL.md entry in the approval list. */
    const val GLOBAL_MEMORY_ID = "__global_memory__"

    data class BundleItem(
        val id: String,
        val title: String,
        val description: String,
        /** Full SKILL.md markdown for skills; raw GLOBAL.md content for the memory item. */
        val content: String,
    )

    /** Build the shareable bundle JSON from the app's current configuration. */
    fun build(context: Context): JSONObject {
        val app = context.applicationContext as MinisApp
        val skills = JSONArray()
        for (skill in app.skillRepository.skills.value) {
            if (!skill.isEnabled) continue
            if (skill.importSource == SkillRepository.ImportSource.BUNDLED) continue
            skills.put(JSONObject().apply {
                put("id", skill.id)
                put("title", skill.name)
                put("description", skill.description)
                put("content", reconstructSkillMd(skill))
            })
        }
        val global = app.memoryRepository.let { repo ->
            runCatching {
                val f = File(java.io.File(context.filesDir, "minis-global/memory"), "GLOBAL.md")
                if (f.isFile) f.readText(Charsets.UTF_8) else ""
            }.getOrDefault("")
        }
        return JSONObject().apply {
            put(MAGIC, VERSION)
            put("exportedAt", System.currentTimeMillis())
            put("skills", skills)
            if (global.isNotBlank()) put("globalMemory", global)
        }
    }

    /** Rebuild a SKILL.md document from a stored skill (frontmatter + body). */
    private fun reconstructSkillMd(skill: SkillRepository.Skill): String =
        "---\nname: ${skill.name}\nversion: ${skill.version}\ndescription: >-\n  " +
            skill.description.replace("\n", "\n  ") +
            "\n---\n\n" + skill.body

    /** Write [json] under the FileProvider-shared cache dir and return its Uri. */
    fun writeToSharedCache(context: Context, json: String): android.net.Uri {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "minis-agent-bundle.json")
        file.writeText(json, Charsets.UTF_8)
        return FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            file,
        )
    }

    /** Fire the system share sheet with the bundle file. */
    fun share(context: Context) {
        val json = build(context).toString(2)
        val uri = writeToSharedCache(context, json)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Minis agent bundle")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Parse staged file text as an agent bundle, or null when it is not one. */
    fun parse(text: String): JSONObject? {
        val obj = try {
            JSONObject(text)
        } catch (_: Exception) {
            return null
        }
        return if (obj.optInt(MAGIC, 0) >= 1) obj else null
    }

    /** Flatten a parsed bundle into approval-list items (skills + memory). */
    fun itemsOf(bundle: JSONObject): List<BundleItem> {
        val out = mutableListOf<BundleItem>()
        val skills = bundle.optJSONArray("skills")
        if (skills != null) {
            for (i in 0 until skills.length()) {
                val s = skills.optJSONObject(i) ?: continue
                out.add(
                    BundleItem(
                        id = s.optString("id"),
                        title = s.optString("title"),
                        description = s.optString("description"),
                        content = s.optString("content"),
                    )
                )
            }
        }
        val global = bundle.optString("globalMemory", "")
        if (global.isNotBlank()) {
            out.add(BundleItem(GLOBAL_MEMORY_ID, "GLOBAL.md", "Curated global memory", global))
        }
        return out
    }

    /** Install the receiver-approved items. Returns the count installed. */
    fun installApproved(context: Context, items: List<BundleItem>): Int {
        val app = context.applicationContext as MinisApp
        var count = 0
        for (item in items) {
            if (item.id == GLOBAL_MEMORY_ID) {
                runCatching {
                    val f = File(java.io.File(context.filesDir, "minis-global/memory"), "GLOBAL.md")
                    f.parentFile?.mkdirs()
                    f.writeText(item.content, Charsets.UTF_8)
                }.onSuccess { count++ }
            } else {
                app.skillRepository.importFromContent(
                    item.content,
                    source = SkillRepository.ImportSource.FILE,
                )?.let { count++ }
            }
        }
        return count
    }
}
