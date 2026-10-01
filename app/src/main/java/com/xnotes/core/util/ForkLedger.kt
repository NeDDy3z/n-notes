package com.xnotes.core.util

import java.lang.ref.WeakReference

/** The forks an editor made, so a save still aimed at the file a document forked away from lands in its fork. */
class ForkLedger {

    private class Forward(val to: String, val owner: WeakReference<Any>)

    private val forwards = HashMap<String, Forward>()

    /** Where [owner]'s save aimed at [uri] belongs: the newest fork [owner] made from it, else [uri] itself. */
    @Synchronized
    fun target(uri: String, owner: Any): String {
        var at = uri
        repeat(forwards.size) {
            val next = forwards[at]?.takeIf { it.owner.get() === owner } ?: return at
            at = next.to
        }
        return at
    }

    /** Record that [owner] forked [from] into the new file [to]. */
    @Synchronized
    fun record(from: String, to: String, owner: Any) {
        forwards.remove(to) // a uri reused by this new file must not still forward elsewhere
        forwards[from] = Forward(to, WeakReference(owner))
    }
}
