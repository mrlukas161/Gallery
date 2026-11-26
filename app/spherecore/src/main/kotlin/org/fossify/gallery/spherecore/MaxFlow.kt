package org.fossify.gallery.spherecore

/** Dinicov maximálny tok na grafe s celočíselnými kapacitami (pole hrán, bez objektov). */
internal class MaxFlow(private val n: Int, edgeCapacityHint: Int) {
    private var head = IntArray(n) { -1 }
    private var to = IntArray(maxOf(16, edgeCapacityHint * 2))
    private var cap = IntArray(maxOf(16, edgeCapacityHint * 2))
    private var nxt = IntArray(maxOf(16, edgeCapacityHint * 2))
    private var ec = 0
    private val level = IntArray(n)
    private val cur = IntArray(n)
    private val queue = IntArray(n)

    private fun grow() {
        val ns = to.size * 2
        to = to.copyOf(ns); cap = cap.copyOf(ns); nxt = nxt.copyOf(ns)
    }

    /** Hrana u→v s kapacitou c a opačná v→u s kapacitou rc (rc = c pre neorientovanú). */
    fun addEdge(u: Int, v: Int, c: Int, rc: Int = 0) {
        if (ec + 2 > to.size) grow()
        to[ec] = v; cap[ec] = c; nxt[ec] = head[u]; head[u] = ec++
        to[ec] = u; cap[ec] = rc; nxt[ec] = head[v]; head[v] = ec++
    }

    private fun bfs(s: Int, t: Int): Boolean {
        java.util.Arrays.fill(level, -1)
        var qh = 0
        var qt = 0
        queue[qt++] = s
        level[s] = 0
        while (qh < qt) {
            val u = queue[qh++]
            var e = head[u]
            while (e >= 0) {
                val v = to[e]
                if (cap[e] > 0 && level[v] < 0) {
                    level[v] = level[u] + 1
                    queue[qt++] = v
                }
                e = nxt[e]
            }
        }
        return level[t] >= 0
    }

    /** Spustí tok; potom [reachableFromSource] hovorí, ktoré uzly ostali na strane zdroja. */
    fun run(s: Int, t: Int, cancel: () -> Unit = {}): Long {
        var flow = 0L
        val stackE = IntArray(n + 1)
        var rounds = 0
        while (bfs(s, t)) {
            if (++rounds % 4 == 0) cancel()
            System.arraycopy(head, 0, cur, 0, n)
            // iteratívne DFS s cestou v stackE
            while (true) {
                var depth = 0
                var u = s
                var found = false
                while (true) {
                    if (u == t) { found = true; break }
                    var advanced = false
                    while (cur[u] >= 0) {
                        val e = cur[u]
                        val v = to[e]
                        if (cap[e] > 0 && level[v] == level[u] + 1) {
                            stackE[depth++] = e
                            u = v
                            advanced = true
                            break
                        }
                        cur[u] = nxt[e]
                    }
                    if (!advanced) {
                        if (depth == 0) break
                        // ústup
                        level[u] = -1
                        val e = stackE[--depth]
                        u = to[e xor 1]
                        cur[u] = nxt[cur[u]]
                    }
                }
                if (!found) break
                var f = Int.MAX_VALUE
                for (d in 0 until depth) f = minOf(f, cap[stackE[d]])
                for (d in 0 until depth) {
                    val e = stackE[d]
                    cap[e] -= f
                    cap[e xor 1] += f
                }
                flow += f
            }
        }
        return flow
    }

    /** Po [run]: uzly dosiahnuteľné zo zdroja v zvyškovom grafe. */
    fun reachableFromSource(s: Int): BooleanArray {
        val seen = BooleanArray(n)
        var qh = 0
        var qt = 0
        queue[qt++] = s
        seen[s] = true
        while (qh < qt) {
            val u = queue[qh++]
            var e = head[u]
            while (e >= 0) {
                val v = to[e]
                if (cap[e] > 0 && !seen[v]) { seen[v] = true; queue[qt++] = v }
                e = nxt[e]
            }
        }
        return seen
    }
}
