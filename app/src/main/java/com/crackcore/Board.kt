package com.crackcore

import kotlin.math.max
import kotlin.math.min

/**
 * Поле хранится как карта координат относительно ЦЕНТРА. Для размера N диапазон -N/2 .. -N/2+N-1,
 * поэтому при расширении/сжатии символы остаются на своих местах относительно центра.
 */
class BoardSystem {
    var n = 3
    var lo = -1
    val cells = HashMap<Int, Int>() // 1 = X, 2 = O
    val birth = HashMap<Int, Float>()
    var cx = 0
    var cy = 0

    // визуальная анимация размера
    var fromN = 3f
    var fromLo = -1f
    var toN = 3f
    var toLo = -1f
    var vN = 3f
    var vLo = -1f
    var ease = 1f
    private var animT = 1f
    private var animDur = 0.5f
    private var sharp = false

    // 2D -> 3D
    var cubeTarget = 0f
    var cube = 0f

    companion object {
        fun key(x: Int, y: Int) = ((x + 128) shl 8) or (y + 128)
        fun kx(k: Int) = (k shr 8) - 128
        fun ky(k: Int) = (k and 255) - 128
        private val DIRS = arrayOf(intArrayOf(1, 0), intArrayOf(0, 1), intArrayOf(1, 1), intArrayOf(1, -1))
    }

    fun inRange(x: Int, y: Int) = x >= lo && x < lo + n && y >= lo && y < lo + n

    fun get(x: Int, y: Int): Int = if (inRange(x, y)) (cells[key(x, y)] ?: 0) else 0

    fun place(x: Int, y: Int, who: Int, now: Float): Boolean {
        if (!inRange(x, y) || get(x, y) != 0) return false
        val k = key(x, y)
        cells[k] = who
        birth[k] = now
        return true
    }

    fun winLen() = if (n <= 3) 3 else 4

    fun isFull() = cells.size >= n * n

    fun clear() {
        cells.clear()
        birth.clear()
        cx = cx.coerceIn(lo, lo + n - 1)
        cy = cy.coerceIn(lo, lo + n - 1)
    }

    /** Меняет размер. Возвращает символы, оказавшиеся за пределами новой области (они исчезают). */
    fun resize(newN: Int, dur: Float, isSharp: Boolean): List<IntArray> {
        val newLo = -(newN / 2)
        fromN = vN
        fromLo = vLo
        toN = newN.toFloat()
        toLo = newLo.toFloat()
        animT = 0f
        animDur = max(dur, 0.05f)
        sharp = isSharp
        val removed = ArrayList<IntArray>()
        val it = cells.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            val x = kx(e.key)
            val y = ky(e.key)
            if (x < newLo || x >= newLo + newN || y < newLo || y >= newLo + newN) {
                removed.add(intArrayOf(x, y, e.value))
                birth.remove(e.key)
                it.remove()
            }
        }
        n = newN
        lo = newLo
        cx = cx.coerceIn(lo, lo + n - 1)
        cy = cy.coerceIn(lo, lo + n - 1)
        return removed
    }

    fun update(dt: Float) {
        if (animT < 1f) animT = min(1f, animT + dt / animDur)
        val e = if (sharp) {
            val q = 1f - animT
            1f - q * q * q * q
        } else {
            if (animT < 0.5f) 4f * animT * animT * animT else 1f - (-2f * animT + 2f).let { it * it * it } / 2f
        }
        ease = e
        vN = fromN + (toN - fromN) * e
        vLo = fromLo + (toLo - fromLo) * e
        cube += (cubeTarget - cube) * min(1f, dt * 2.2f)
    }

    /** Линия длиной >= winLen через клетку (x,y), либо null. */
    fun lineThrough(x: Int, y: Int, who: Int): List<IntArray>? {
        val need = winLen()
        for (d in DIRS) {
            val line = ArrayList<IntArray>()
            line.add(intArrayOf(x, y))
            for (sg in intArrayOf(1, -1)) {
                var px = x + d[0] * sg
                var py = y + d[1] * sg
                while (inRange(px, py) && get(px, py) == who) {
                    line.add(intArrayOf(px, py))
                    px += d[0] * sg
                    py += d[1] * sg
                }
            }
            if (line.size >= need) return line
        }
        return null
    }

    /** Ближайшая пустая клетка к (x,y): детерминированный обход колец по Чебышёву. */
    fun nearestEmpty(x: Int, y: Int): IntArray? {
        for (r in 0..n) {
            for (dy in -r..r) {
                for (dx in -r..r) {
                    if (max(kotlin.math.abs(dx), kotlin.math.abs(dy)) != r) continue
                    val px = x + dx
                    val py = y + dy
                    if (inRange(px, py) && get(px, py) == 0) return intArrayOf(px, py)
                }
            }
        }
        return null
    }
}
