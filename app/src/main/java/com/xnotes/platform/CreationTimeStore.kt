package com.xnotes.platform

import com.xnotes.core.util.DocKeys
import org.json.JSONObject

/**
 * Remembers when each note/folder was created, keyed by document identity (the same
 * authority+id key as [ViewStateStore] and the explorer's per-note view), so the
 * grid can order by creation rather than last-modified. The Storage Access Framework
 * exposes only a last-modified time, never a creation time, so the app tracks it
 * itself: an item the app creates — and any item it later *discovers* under the granted
 * folder — is stamped the first time it's seen ([stampMissing]), and keeps that stamp
 * thereafter. Held in memory and mirrored to a small JSON file ([JsonStore.createdTimes]);
 * [clear]ed when the user forgets the folder, since the keys are only meaningful for it.
 */
class CreationTimeStore(private val store: JsonStore) {

    private val times: MutableMap<String, Long> = load()

    private fun load(): MutableMap<String, Long> {
        val out = HashMap<String, Long>()
        val o = store.read()
        for (key in o.keys()) out[key] = o.optLong(key, 0L)
        return out
    }

    @Synchronized
    fun get(key: String): Long? = times[key]

    /** Assign [now] to every key not seen before; persist once if anything changed. */
    @Synchronized
    fun stampMissing(keys: Collection<String>, now: Long) {
        var changed = false
        for (k in keys) if (k !in times) { times[k] = now; changed = true }
        if (changed) store.write(toJson())
    }

    /** Record the created time a file carries itself, which outranks any first-seen stamp. */
    @Synchronized
    fun put(key: String, time: Long) {
        if (times[key] == time) return
        times[key] = time
        store.write(toJson())
    }

    /** Carry created times across a rename or move that changed the document id, a folder's descendants included. */
    @Synchronized
    fun rekeyTree(from: String, to: String) {
        val moves = times.keys.mapNotNull { k -> DocKeys.moved(k, from, to)?.let { k to it } }
        if (moves.isEmpty()) return
        val values = moves.map { (old, _) -> times.remove(old) }
        moves.forEachIndexed { i, (_, new) -> values[i]?.let { times[new] = it } }
        store.write(toJson())
    }

    /** Forget the created time for every key matching [predicate] — a deleted file, or a deleted folder's whole subtree. */
    @Synchronized
    fun removeMatching(predicate: (String) -> Boolean) {
        if (times.keys.removeAll(predicate)) store.write(toJson())
    }

    /** Forget every created time (e.g. when the granted folder is released). */
    @Synchronized
    fun clear() {
        times.clear()
        store.write(JSONObject())
    }

    private fun toJson(): JSONObject {
        val o = JSONObject()
        for ((k, v) in times) o.put(k, v)
        return o
    }
}
