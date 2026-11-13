package com.xnotes.sync.merge

/**
 * Three-way merge of two edited page lists against the list both started from. Pages are compared
 * by fingerprint only. Where both sides changed the same page, the local version of that run of pages
 * is kept and the run is reported, so the caller can save the remote version as a conflict copy.
 */
object PageMerge {
    /** One page of the result: which side it comes from and its index there. */
    data class Ref(val local: Boolean, val index: Int)

    /** [clashed] holds the result indexes of the local pages kept where both sides changed the same page. */
    class Result(val pages: List<Ref>, val clashed: List<Int>)

    /** Above this many cells per alignment the merge is skipped (the caller keeps a copy instead). */
    private const val MAX_CELLS = 4_000_000L

    /** Whether the page lists are short enough to align; when not, [merge] returns null. */
    fun fits(base: List<String>, local: List<String>, remote: List<String>): Boolean =
        base.size.toLong() * local.size <= MAX_CELLS && base.size.toLong() * remote.size <= MAX_CELLS

    fun merge(base: List<String>, local: List<String>, remote: List<String>): Result? {
        val toLocal = align(base, local) ?: return null
        val toRemote = align(base, remote) ?: return null
        val out = ArrayList<Ref>()
        val clashed = ArrayList<Int>()
        var b = 0
        var l = 0
        var r = 0

        val localToBase = IntArray(local.size) { -1 }.also { m -> toLocal.forEachIndexed { b, l -> if (l >= 0) m[l] = b } }
        val remoteToBase = IntArray(remote.size) { -1 }.also { m -> toRemote.forEachIndexed { b, r -> if (r >= 0) m[r] = b } }

        // A moved page reads as dropped and re-added. If the other side changed or dropped it, the merge would keep
        // the old moved copy beside the edit, so that is left to the conflict copy.
        val baseSet = base.toHashSet()
        fun movedAgainstChange(side: List<String>, toBase: IntArray, other: List<String>): Boolean {
            val otherSet = other.toHashSet()
            return side.indices.any { toBase[it] < 0 && side[it] in baseSet && side[it] !in otherSet }
        }
        if (movedAgainstChange(local, localToBase, remote) || movedAgainstChange(remote, remoteToBase, local)) return null

        /** A page one side added or changed, placed between the base pages that side kept around it. */
        class New(val after: Int, val before: Int, val ref: Ref)

        fun newPages(from: Int, to: Int, toBase: IntArray, isLocal: Boolean, bEnd: Int): List<New> {
            val out = ArrayList<New>()
            for (i in from until to) {
                if (toBase[i] >= 0) continue
                val after = (i - 1 downTo from).firstOrNull { toBase[it] >= 0 }?.let { toBase[it] } ?: -1
                val before = (i + 1 until to).firstOrNull { toBase[it] >= 0 }?.let { toBase[it] } ?: bEnd
                out.add(New(after, before, Ref(isLocal, i)))
            }
            return out
        }

        fun chunk(bEnd: Int, lEnd: Int, rEnd: Int) {
            val bc = base.subList(b, bEnd)
            val lc = local.subList(l, lEnd)
            val rc = remote.subList(r, rEnd)
            when {
                lc == bc -> (r until rEnd).forEach { out.add(Ref(false, it)) }
                rc == bc || lc == rc -> (l until lEnd).forEach { out.add(Ref(true, it)) }
                else -> {
                    // Every base page here was dropped or changed by at least one side, so only the pages
                    // the sides added or changed remain, ordered by where each side put them.
                    val fromLocal = newPages(l, lEnd, localToBase, true, bEnd)
                    val fromRemote = newPages(r, rEnd, remoteToBase, false, bEnd)
                    val sameBaseChanged = (b until bEnd).any { toLocal[it] < 0 && toRemote[it] < 0 }
                    if (sameBaseChanged && fromLocal.isNotEmpty() && fromRemote.isNotEmpty()) {
                        // Both changed the same page: this side's run stays whole, the other's is left to the conflict copy.
                        // Reported are the pages whose other version that drops; page for page when the run kept its length.
                        val sameShape = lEnd - l == bEnd - b
                        for ((i, li) in (l until lEnd).withIndex()) {
                            out.add(Ref(true, li))
                            if (!sameShape || toRemote[b + i] < 0) clashed.add(out.size - 1)
                        }
                    } else {
                        (fromLocal + fromRemote).sortedWith(compareBy({ it.after }, { it.before }, { !it.ref.local })).forEach { out.add(it.ref) }
                    }
                }
            }
        }

        while (true) {
            var k = b
            while (k < base.size && (toLocal[k] < 0 || toRemote[k] < 0)) k++
            if (k == base.size) {
                chunk(base.size, local.size, remote.size)
                break
            }
            chunk(k, toLocal[k], toRemote[k])
            out.add(Ref(true, toLocal[k]))
            b = k + 1
            l = toLocal[k] + 1
            r = toRemote[k] + 1
        }
        return Result(out, clashed)
    }

    /** For each index of [a], the index of its partner in [b] on a longest common subsequence, or -1. */
    private fun align(a: List<String>, b: List<String>): IntArray? {
        if (a.size.toLong() * b.size > MAX_CELLS) return null
        val n = a.size
        val m = b.size
        val len = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            len[i][j] = if (a[i] == b[j]) len[i + 1][j + 1] + 1 else maxOf(len[i + 1][j], len[i][j + 1])
        }
        val match = IntArray(n) { -1 }
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> { match[i] = j; i++; j++ }
                len[i + 1][j] >= len[i][j + 1] -> i++
                else -> j++
            }
        }
        return match
    }
}
