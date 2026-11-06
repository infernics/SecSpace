package infernics.notes

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class ChecklistItem(
    val id: String = UUID.randomUUID().toString(),
    val text: String = "",
    val checked: Boolean = false
)

enum class NoteKind { NOTE, LIST }

data class Note(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val body: String = "",
    val checklist: List<ChecklistItem> = emptyList(),
    val kind: NoteKind = NoteKind.NOTE,
    val updatedAt: Long = System.currentTimeMillis()
)

/** Each note is a separate AES-256-GCM encrypted JSON file in private app storage. */
class NoteRepository(context: Context, private val crypto: Crypto) {
    private val dir = File(context.filesDir, "notes").apply { mkdirs() }

    fun loadAll(): List<Note> = dir.listFiles { file -> file.name.endsWith(".enc") }
        .orEmpty()
        .mapNotNull { file ->
            runCatching {
                val json = JSONObject(crypto.decryptToText(file.readBytes()))
                val checklistJson = json.optJSONArray("checklist") ?: JSONArray()
                val checklist = buildList {
                    for (index in 0 until checklistJson.length()) {
                        val item = checklistJson.optJSONObject(index) ?: continue
                        add(
                            ChecklistItem(
                                id = item.optString("id", UUID.randomUUID().toString()),
                                text = item.optString("text", ""),
                                checked = item.optBoolean("checked", false)
                            )
                        )
                    }
                }
                val storedKind = json.optString("kind", "").uppercase()
                val kind = when (storedKind) {
                    "LIST" -> NoteKind.LIST
                    "NOTE" -> NoteKind.NOTE
                    // Backward compatibility: older builds stored checklist rows inside notes.
                    else -> if (checklist.isNotEmpty()) NoteKind.LIST else NoteKind.NOTE
                }
                Note(
                    id = json.getString("id"),
                    title = json.optString("title", ""),
                    body = json.optString("body", ""),
                    checklist = checklist,
                    kind = kind,
                    updatedAt = json.optLong("updatedAt", file.lastModified())
                )
            }.getOrNull()
        }
        .sortedByDescending { it.updatedAt }

    fun save(note: Note) {
        val checklistJson = JSONArray().apply {
            note.checklist.forEach { item ->
                put(
                    JSONObject()
                        .put("id", item.id)
                        .put("text", item.text)
                        .put("checked", item.checked)
                )
            }
        }
        val json = JSONObject()
            .put("id", note.id)
            .put("title", note.title)
            .put("body", note.body)
            .put("checklist", checklistJson)
            .put("kind", note.kind.name)
            .put("updatedAt", note.updatedAt)
        val target = File(dir, "${note.id}.enc")
        val temp = File(dir, "${note.id}.tmp")
        temp.writeBytes(crypto.encrypt(json.toString()))
        if (!temp.renameTo(target)) {
            target.writeBytes(temp.readBytes())
            temp.delete()
        }
    }

    fun delete(id: String) {
        File(dir, "$id.enc").delete()
    }
}
