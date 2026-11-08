package com.xnotes.sync

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Names of the copies sync keeps when a note changed on two devices. */
object SyncConflict {
    // Also matches the older "(Filen conflict 2026-01-01 1200)" copies.
    private val COPY = Regex("""\((sync|filen) conflict \d{4}-\d{2}-\d{2} \d{4}\)""", RegexOption.IGNORE_CASE)

    /** "notes/a.xnote" -> "notes/a (Sync Conflict 2026-11-02 1430).xnote". */
    fun copyPath(path: String, now: Date = Date()): String {
        val tag = " (Sync Conflict ${SimpleDateFormat("yyyy-MM-dd HHmm", Locale.US).format(now)})"
        val slash = path.lastIndexOf('/')
        val dot = path.lastIndexOf('.')
        return if (dot <= slash + 1) path + tag else path.substring(0, dot) + tag + path.substring(dot)
    }

    fun isCopy(name: String): Boolean = COPY.containsMatchIn(name)

    /** Why sync kept a copy. [why] finishes "could not be merged because ..."; %s is the device that kept its version. */
    enum class Reason(val why: String) {
        SAME_PAGES(""),
        BOTH_NEW(""),
        NOT_A_NOTE(""),
        BOOKMARKS("the note has bookmarks or PDF markups, which are not merged page by page"),
        ATTACHMENTS("a file kept in the note outside its pages, such as its PDF, differs between the two versions"),
        NOTE_SETTINGS("note-wide content, such as the text flow, changed on both devices"),
        MOVED("pages were moved on one device and changed or removed on the other"),
        TOO_LONG("the note has too many pages to compare page by page"),
        UNREADABLE("one of the two versions could not be read as a note"),
        CHECK_FAILED("the merged note did not pass its check"),
        NO_BASELINE("%s had no page record of the note from the last sync, as happens after updating the app"),
        OPEN("the note was open in the editor on %s"),
        SAVED_DURING("the note was saved on %s while sync was running"),
    }

    /** What sync knew when it kept a copy: the note it belongs to, when, on which device, why, and for [Reason.SAME_PAGES] the 1-based pages. */
    data class Record(val original: String, val at: Long, val device: String, val reason: Reason, val pages: List<Int> = emptyList())

    /** The top folder's sidecar file holding a [Record] per copy path; synced and merged like the other sidecars. */
    const val RECORDS_PATH = ".xnote/conflicts.json"

    // shortcut: records are never pruned (and a merge with another device would bring them back anyway); prune by missing copy if the file grows.
    fun withRecords(existing: String?, records: Map<String, Record>): String {
        val out = runCatching { JSONObject(existing ?: "{}") }.getOrDefault(JSONObject())
        for ((copy, r) in records) out.put(copy, JSONObject()
            .put("original", r.original).put("at", r.at).put("device", r.device).put("reason", r.reason.name).put("pages", JSONArray(r.pages)))
        return out.toString()
    }

    fun recordOf(json: String, copyPath: String): Record? = runCatching {
        val o = JSONObject(json).getJSONObject(copyPath)
        val pages = o.optJSONArray("pages")
        Record(o.getString("original"), o.getLong("at"), o.getString("device"), Reason.valueOf(o.getString("reason")),
            if (pages == null) emptyList() else (0 until pages.length()).map { pages.getInt(it) })
    }.getOrNull()

    /** The dialog text for the copy named [copyName]; [record] is null for a copy made before records were kept. */
    fun explain(copyName: String, record: Record?): String {
        if (record == null) {
            return "Sync kept this copy because the note was changed on two devices before they synced. " +
                "No details were saved for it, as it was made by an older version of the app."
        }
        val note = "\"${record.original.substringAfterLast('/')}\""
        val dev = record.device
        return when (record.reason) {
            Reason.SAME_PAGES -> {
                val pages = pageList(record.pages)
                "$pages of $note ${if (record.pages.size == 1) "was" else "were"} changed on $dev and on another device before they synced. " +
                    "Everything else from both devices was merged into $note, which keeps $dev's version of ${pages.replaceFirstChar { it.lowercase() }}. " +
                    "This copy is the other device's whole note: take what you need from it, then delete it."
            }
            Reason.BOTH_NEW -> "$note was created on $dev and on another device, with different content, before they synced. " +
                "$note is the one from $dev; this copy is the one from the other device."
            Reason.NOT_A_NOTE -> "$note was changed on $dev and on another device before they synced. Only notes are merged, " +
                "so $note keeps $dev's version and this copy is the other device's version."
            else -> "$note was changed on $dev and on another device before they synced, and the changes could not be merged because " +
                "${record.reason.why.format(dev)}. $note keeps $dev's version; this copy is the other device's version."
        }
    }

    /** "Page 2", "Pages 2 and 3", "Pages 2, 3 and 5". */
    private fun pageList(pages: List<Int>): String = when (pages.size) {
        0 -> "A page"
        1 -> "Page ${pages[0]}"
        else -> "Pages ${pages.dropLast(1).joinToString(", ")} and ${pages.last()}"
    }
}
