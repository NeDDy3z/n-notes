package com.xnotes.platform

import com.xnotes.canvas.ViewOverrides
import org.json.JSONObject

/**
 * Remembers each note's last view — zoom, scroll and its View-menu overrides — keyed by
 * document identity, so a note in the granted folder reopens exactly where (and how) the
 * user left it. Held in memory and mirrored to a small JSON file ([JsonStore.viewStates]);
 * [clear]ed when the user forgets the folder, since the keys are only meaningful for that
 * folder's documents.
 */
class ViewStateStore(private val store: JsonStore) {

    class View(val zoom: Double, val scrollX: Double, val scrollY: Double, val overrides: ViewOverrides)

    private val views: MutableMap<String, View> = load()

    private fun load(): MutableMap<String, View> {
        val out = HashMap<String, View>()
        val o = store.read()
        for (key in o.keys()) {
            val e = o.optJSONObject(key) ?: continue
            out[key] = View(
                e.optDouble("zoom", 0.0),
                e.optDouble("scrollX", 0.0),
                e.optDouble("scrollY", 0.0),
                ViewOverridesJson.read(e),
            )
        }
        return out
    }

    @Synchronized
    fun get(key: String): View? = views[key]

    @Synchronized
    fun put(key: String, zoom: Double, scrollX: Double, scrollY: Double, overrides: ViewOverrides) {
        views[key] = View(zoom, scrollX, scrollY, overrides)
        store.write(toJson())
    }

    /** Forget one note's remembered view (e.g. when its file is deleted). */
    @Synchronized
    fun remove(key: String) {
        if (views.remove(key) != null) store.write(toJson())
    }

    /** Forget the remembered view for every note whose key matches [predicate] — a deleted file, or a deleted folder's whole subtree. */
    @Synchronized
    fun removeMatching(predicate: (String) -> Boolean) {
        if (views.keys.removeAll(predicate)) store.write(toJson())
    }

    /** Carry remembered views across a rename or move that changed the document id, a folder's descendants included. */
    @Synchronized
    fun rekeyTree(from: String, to: String) {
        val moves = views.keys.mapNotNull { k -> com.xnotes.core.util.DocKeys.moved(k, from, to)?.let { k to it } }
        if (moves.isEmpty()) return
        val values = moves.map { (old, _) -> views.remove(old) }
        moves.forEachIndexed { i, (_, new) -> values[i]?.let { views[new] = it } }
        store.write(toJson())
    }

    /** Forget every remembered view (e.g. when the granted folder is released). */
    @Synchronized
    fun clear() {
        views.clear()
        store.write(JSONObject())
    }

    private fun toJson(): JSONObject {
        val o = JSONObject()
        for ((k, v) in views) {
            val e = JSONObject().put("zoom", v.zoom).put("scrollX", v.scrollX).put("scrollY", v.scrollY)
            o.put(k, ViewOverridesJson.write(e, v.overrides))
        }
        return o
    }
}
