package com.crackcore

import kotlin.math.abs

class Note(val time: Double, val lane: Int, val accent: Boolean) {
    var state = 0 // 0 ожидает, 1 попадание, 2 пропущена
}

enum class Judge { PERFECT, GREAT, GOOD, MISS }

class Hit(val note: Note, val judge: Judge)

class Press(val lane: Int, val time: Double)

class RhythmSystem {
    companion object {
        const val PERFECT = 0.045
        const val GREAT = 0.09
        const val GOOD = 0.14
    }

    val notes = ArrayList<Note>(900)
    private var head = 0
    var combo = 0
    var bestCombo = 0
    val counts = IntArray(4)

    init {
        build()
    }

    private fun build() {
        var prev = 1
        for (bar in 1 until Tempo.BARS - 1) {
            val sec = Pattern.section(bar)
            for (s in 0 until 16) {
                val gs = bar * 16 + s
                val r = Pattern.rnd(gs, 77)
                val accent = s % 4 == 0
                val want = when (sec) {
                    0 -> (s == 6 || s == 14) && r < 0.5f
                    1 -> s % 2 == 0 && r > 0.12f
                    2 -> s % 2 == 0 || (bar % 4 == 3 && s >= 8)
                    3 -> s == 3 || s == 6 || s == 8 || s == 10 || s == 12 || s == 14 || (s == 15 && r < 0.6f)
                    4 -> (s % 4 != 3) || r < 0.35f
                    5 -> s % 3 == 0 || s % 4 == 0
                    6 -> r < 0.72f
                    else -> r < 0.8f
                }
                if (!accent && !want) continue
                val t = Tempo.stepStart[gs]
                var lane: Int
                if (sec == 4) {
                    lane = if (bar % 2 == 0) gs % 4 else 3 - (gs % 4)
                } else if (sec == 5) {
                    lane = (gs * 5 / 3) and 3
                } else {
                    lane = (Pattern.hash(gs, 9) ushr 3) and 3
                    if (lane == prev && r < 0.7f) lane = (lane + 1 + ((Pattern.hash(gs, 10) ushr 3) % 3)) and 3
                }
                prev = lane
                notes.add(Note(t, lane, accent))
                val double = when (sec) {
                    3 -> accent && s == 0 && bar % 2 == 1
                    5 -> s % 12 == 0
                    7 -> accent && Pattern.rnd(gs, 31) < 0.5f
                    else -> false
                }
                if (double) notes.add(Note(t, (lane + 2) and 3, accent))
            }
        }
    }

    private fun advance() {
        while (head < notes.size && notes[head].state != 0) head++
    }

    fun tryHit(lane: Int, t: Double): Hit? {
        var best: Note? = null
        var bestD = 9.0
        var i = head
        while (i < notes.size) {
            val n = notes[i]
            if (n.time > t + GOOD) break
            if (n.state == 0 && n.lane == lane) {
                val d = abs(n.time - t)
                if (d <= GOOD && d < bestD) {
                    best = n
                    bestD = d
                }
            }
            i++
        }
        if (best == null) return null
        best.state = 1
        val j = when {
            bestD <= PERFECT -> Judge.PERFECT
            bestD <= GREAT -> Judge.GREAT
            else -> Judge.GOOD
        }
        counts[j.ordinal]++
        combo++
        if (combo > bestCombo) bestCombo = combo
        advance()
        return Hit(best, j)
    }

    fun sweep(t: Double, onMiss: () -> Unit) {
        var i = head
        while (i < notes.size) {
            val n = notes[i]
            if (n.time > t - GOOD) break
            if (n.state == 0) {
                n.state = 2
                counts[3]++
                combo = 0
                onMiss()
            }
            i++
        }
        advance()
    }
}
