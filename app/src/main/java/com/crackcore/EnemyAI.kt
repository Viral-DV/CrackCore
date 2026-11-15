package com.crackcore

import kotlin.math.max
import kotlin.math.min

/**
 * Противник управляет O. Никаких LLM и сети: minimax + alpha-beta с эвристикой по окнам длины k.
 * Сначала мгновенная победа, затем блок победы X, затем поиск.
 */
class EnemyAI {
    private val weights = intArrayOf(0, 1, 10, 100, 1000, 10000, 100000)
    private val dirs = arrayOf(intArrayOf(1, 0), intArrayOf(0, 1), intArrayOf(1, 1), intArrayOf(1, -1))
    private var n = 3
    private var k = 3
    private var g = IntArray(9)

    fun choose(b: BoardSystem): IntArray? {
        n = b.n
        k = b.winLen()
        g = IntArray(n * n)
        for ((key, v) in b.cells) {
            val x = BoardSystem.kx(key) - b.lo
            val y = BoardSystem.ky(key) - b.lo
            if (x in 0 until n && y in 0 until n) g[y * n + x] = v
        }
        var empty = 0
        var any = false
        for (v in g) {
            if (v == 0) empty++ else any = true
        }
        if (empty == 0) return null
        if (!any) return out((n / 2) * n + n / 2, b)

        val win = findWin(2)
        if (win >= 0) return out(win, b)
        val block = findWin(1)
        if (block >= 0) return out(block, b)

        val depth = if (n <= 3) min(empty, 6) else if (n <= 5) 4 else 3
        val cand = candidates(2)
        if (cand.isEmpty()) return null
        var best = cand[0]
        var bestScore = Int.MIN_VALUE
        for (c in cand) {
            g[c] = 2
            val s = search(depth - 1, Int.MIN_VALUE + 1, Int.MAX_VALUE, 1)
            g[c] = 0
            if (s > bestScore) {
                bestScore = s
                best = c
            }
        }
        return out(best, b)
    }

    private fun out(idx: Int, b: BoardSystem) = intArrayOf(idx % n + b.lo, idx / n + b.lo)

    private fun findWin(who: Int): Int {
        for (i in g.indices) {
            if (g[i] != 0) continue
            g[i] = who
            val w = wins(i, who)
            g[i] = 0
            if (w) return i
        }
        return -1
    }

    private fun wins(idx: Int, who: Int): Boolean {
        val x = idx % n
        val y = idx / n
        for (d in dirs) {
            var cnt = 1
            for (sg in intArrayOf(1, -1)) {
                var px = x + d[0] * sg
                var py = y + d[1] * sg
                while (px in 0 until n && py in 0 until n && g[py * n + px] == who) {
                    cnt++
                    px += d[0] * sg
                    py += d[1] * sg
                }
            }
            if (cnt >= k) return true
        }
        return false
    }

    private fun search(depth: Int, alphaIn: Int, betaIn: Int, turn: Int): Int {
        if (depth <= 0) return eval()
        val cand = candidates(turn)
        if (cand.isEmpty()) return 0
        var alpha = alphaIn
        var beta = betaIn
        if (turn == 2) {
            var best = Int.MIN_VALUE
            for (c in cand) {
                g[c] = 2
                val s = if (wins(c, 2)) 1_000_000 + depth else search(depth - 1, alpha, beta, 1)
                g[c] = 0
                if (s > best) best = s
                if (best > alpha) alpha = best
                if (alpha >= beta) break
            }
            return best
        } else {
            var best = Int.MAX_VALUE
            for (c in cand) {
                g[c] = 1
                val s = if (wins(c, 1)) -1_000_000 - depth else search(depth - 1, alpha, beta, 2)
                g[c] = 0
                if (s < best) best = s
                if (best < beta) beta = best
                if (alpha >= beta) break
            }
            return best
        }
    }

    private fun near(i: Int): Boolean {
        val x = i % n
        val y = i / n
        for (dy in -2..2) for (dx in -2..2) {
            val px = x + dx
            val py = y + dy
            if (px in 0 until n && py in 0 until n && g[py * n + px] != 0) return true
        }
        return false
    }

    private fun candidates(turn: Int): IntArray {
        var empty = 0
        for (v in g) if (v == 0) empty++
        val list = ArrayList<Int>()
        for (i in g.indices) {
            if (g[i] == 0 && (empty <= 9 || near(i))) list.add(i)
        }
        if (list.isEmpty()) return IntArray(0)
        if (n <= 3 || list.size <= 4) return list.toIntArray()
        val scored = list.map { i ->
            g[i] = turn
            val s = eval()
            g[i] = 0
            Pair(i, if (turn == 2) s else -s)
        }.sortedByDescending { it.second }
        return scored.take(if (n <= 5) 8 else 7).map { it.first }.toIntArray()
    }

    private fun eval(): Int {
        var s = 0
        for (y in 0 until n) {
            for (x in 0 until n) {
                if (x + k <= n) s += window(x, y, 1, 0)
                if (y + k <= n) s += window(x, y, 0, 1)
                if (x + k <= n && y + k <= n) s += window(x, y, 1, 1)
                if (x - k + 1 >= 0 && y + k <= n) s += window(x, y, -1, 1)
            }
        }
        return s
    }

    private fun window(x: Int, y: Int, dx: Int, dy: Int): Int {
        var o = 0
        var xx = 0
        var px = x
        var py = y
        for (i in 0 until k) {
            val v = g[py * n + px]
            if (v == 2) o++ else if (v == 1) xx++
            px += dx
            py += dy
        }
        return if (o > 0 && xx > 0) 0 else if (o > 0) weights[o] else if (xx > 0) -weights[xx] * 11 / 10 else 0
    }
}
