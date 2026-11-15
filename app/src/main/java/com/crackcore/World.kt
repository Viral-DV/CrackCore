package com.crackcore

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class World(val music: MusicController, val layout: Layout) {
    private class Action(val time: Double, val type: Int, val x: Int, val y: Int, val q: Int)

    val board = BoardSystem()
    val rhythm = RhythmSystem()
    val ai = EnemyAI()
    val vfx = Vfx()

    var integrity = 1f
    var xWins = 0
    var oWins = 0
    var ended = 0 // 0 идёт, 1 победа, 2 поражение
    var time = 0f
    var songT = 0.0
    var section = 0
    var judge: Judge? = null
    var judgeAge = 9f
    var aiPending = false
    var aiTx = 0
    var aiTy = 0
    val btn = FloatArray(4)

    private var lastStep = -1
    private var aiExtra = 0
    private val actions = ArrayList<Action>()
    private val tmp = FloatArray(2)

    init {
        vfx.setLayout(layout)
        vfx.ambient = 250
        syncLayout()
    }

    private fun syncLayout() {
        val w = vfx.warp
        w.cx = layout.bx
        w.cy = layout.by
        w.cell = layout.boardPx / board.vN
        w.fov = 2.5f * board.vN + 3f
        w.span = max(2f, board.vN * 0.45f)
    }

    fun cellScreen(x: Int, y: Int, out: FloatArray) {
        val c = board.vLo + (board.vN - 1f) / 2f
        val z = -board.cube * board.vN / 2f
        vfx.warp.map(x - c, y - c, z, out, 0)
    }

    fun update(t: Double, dt: Float, presses: List<Press>) {
        songT = t
        time += dt
        judgeAge += dt
        for (i in 0 until 4) btn[i] = max(0f, btn[i] - dt * 6f)
        syncLayout()
        board.update(dt)
        if (board.cubeTarget > 0.5f) {
            vfx.warp.yaw.t = 0.55f * sin(time * 0.7f) + 0.3f
            vfx.warp.pitch.t = 0.45f + 0.15f * sin(time * 0.5f)
        }
        vfx.update(dt)
        if (ended != 0) return

        val cur = Tempo.stepAt(t)
        while (lastStep < cur) {
            lastStep++
            if (lastStep < Tempo.STEPS) onStep(lastStep)
        }
        for (p in presses) handlePress(p)
        rhythm.sweep(t) { onMiss() }
        runActions(t)

        if (integrity <= 0f) finish(2)
        else if (t >= Tempo.total) finish(if (xWins >= oWins) 1 else 2)
    }

    private fun finish(r: Int) {
        ended = r
        vfx.big(1f)
        if (r == 2) {
            for (k in board.cells.keys) {
                cellScreen(BoardSystem.kx(k), BoardSystem.ky(k), tmp)
                vfx.burst(tmp[0], tmp[1], 40, 900f * layout.u, 1f, true)
            }
            music.silence()
        } else {
            music.fadeToDrone()
        }
    }

    // ---------- ввод ----------
    private fun handlePress(p: Press) {
        btn[p.lane] = 1f
        when (p.lane) {
            0 -> board.cx -= 1
            1 -> board.cy += 1
            2 -> board.cy -= 1
            else -> board.cx += 1
        }
        board.cx = board.cx.coerceIn(board.lo, board.lo + board.n - 1)
        board.cy = board.cy.coerceIn(board.lo, board.lo + board.n - 1)
        val hit = rhythm.tryHit(p.lane, p.time) ?: return
        judge = hit.judge
        judgeAge = 0f
        integrity = min(1f, integrity + (if (hit.judge == Judge.GOOD) 0.01f else 0.025f))
        vfx.hitFx(layout.laneX(p.lane), layout.hitY, hit.judge, hit.note.accent)
        if (hit.note.accent) {
            // Perfect/Great выполняются сразу, Good — с задержкой в один шаг
            val delay = if (hit.judge == Judge.GOOD) Tempo.stepDur(max(0, lastStep)) else 0.0
            actions.add(Action(p.time + delay, 0, board.cx, board.cy, hit.judge.ordinal))
        }
    }

    private fun onMiss() {
        integrity -= 0.02f
        judge = Judge.MISS
        judgeAge = 0f
        vfx.missFx()
    }

    // ---------- действия в общем музыкальном времени ----------
    private fun runActions(t: Double) {
        if (actions.isEmpty()) return
        actions.sortWith(compareBy<Action>({ it.time }, { it.type }))
        while (actions.isNotEmpty() && actions[0].time <= t) {
            val a = actions.removeAt(0)
            if (ended != 0) return
            if (a.type == 0) execPlaceX(a) else execCommitO(a)
        }
    }

    private fun execPlaceX(a: Action) {
        var x = a.x
        var y = a.y
        if (!board.inRange(x, y) || board.get(x, y) != 0) {
            val e = board.nearestEmpty(x.coerceIn(board.lo, board.lo + board.n - 1), y.coerceIn(board.lo, board.lo + board.n - 1)) ?: return
            x = e[0]
            y = e[1]
        }
        val q = when (a.q) { 0 -> 1f; 1 -> 0.7f; else -> 0.45f }
        doPlace(x, y, 1, q)
    }

    /** Детерминированное разрешение конфликта: X с меньшей или равной меткой времени обрабатывается раньше O. */
    private fun execCommitO(a: Action) {
        aiPending = false
        var x = a.x
        var y = a.y
        if (!board.inRange(x, y) || board.get(x, y) != 0) {
            val c = ai.choose(board)
            vfx.conflictFx()
            if (c == null) return
            x = c[0]
            y = c[1]
        }
        doPlace(x, y, 2, 0.8f)
    }

    private fun doPlace(x: Int, y: Int, who: Int, q: Float) {
        if (!board.place(x, y, who, time)) return
        cellScreen(x, y, tmp)
        vfx.placeFx(tmp[0], tmp[1], who, q)
        val line = board.lineThrough(x, y, who)
        if (line != null) {
            roundWon(who, line)
        } else if (board.isFull()) {
            clearBoard()
        }
    }

    private fun roundWon(who: Int, line: List<IntArray>) {
        if (who == 1) {
            xWins++
            integrity = min(1f, integrity + 0.1f)
        } else {
            oWins++
        }
        for (c in line) {
            cellScreen(c[0], c[1], tmp)
            vfx.burst(tmp[0], tmp[1], 60, 1100f * layout.u, 0.9f, true)
        }
        vfx.big(1f)
        clearBoard()
    }

    private fun clearBoard() {
        for (k in board.cells.keys) {
            cellScreen(BoardSystem.kx(k), BoardSystem.ky(k), tmp)
            vfx.burst(tmp[0], tmp[1], 14, 600f * layout.u, 0.6f, true)
        }
        board.clear()
        actions.removeAll { it.type == 1 }
        aiPending = false
    }

    // ---------- сценарий уровня ----------
    private fun onStep(step: Int) {
        val bar = step / 16
        val s = step % 16
        val sec = Pattern.section(bar)
        section = sec
        vfx.ambient = 250 + 220 * sec
        if (s == 0) levelEvent(bar)

        if (Pattern.kick(bar, s)) {
            vfx.kick(if (s == 0) 1f else 0.75f, sec)
            if ((sec == 3 || sec == 4 || sec == 6 || sec == 7) && s != 0 && Pattern.rnd(step, 5) < 0.18f) vfx.jolt(sec)
        }
        val sn = Pattern.snare(bar, s)
        if (sn > 0f) vfx.snare(sn, sec)
        val h = Pattern.hat(bar, s)
        if (h > 0f) vfx.hat(sec, h)

        // ИИ не ждёт игрока: выбирает цель и фиксирует её через 2 шага
        val interval = 8
        if (!aiPending && ended == 0 && (s % interval == 0 || aiExtra > 0)) {
            val c = ai.choose(board)
            if (c != null) {
                if (s % interval != 0) aiExtra--
                aiTx = c[0]
                aiTy = c[1]
                aiPending = true
                actions.add(Action(Tempo.stepStart[min(step + 2, Tempo.STEPS)], 1, c[0], c[1], 0))
            }
        }
    }

    private fun resize(nn: Int, dur: Float, sharp: Boolean) {
        if (nn == board.n) {
            vfx.big(0.5f)
            return
        }
        val removed = board.resize(nn, dur, sharp)
        for (r in removed) {
            cellScreen(r[0], r[1], tmp)
            vfx.burst(tmp[0], tmp[1], 50, 1200f * layout.u, 0.9f, true)
        }
        vfx.big(if (sharp) 1f else 0.6f)
    }

    private fun levelEvent(bar: Int) {
        if (bar % 8 == 0) styleFor(bar / 8)
        when (bar) {
            8 -> resize(5, 0.65f, false)
            16 -> resize(12, 1.4f, false)
            22 -> { resize(7, 0.14f, true); vfx.jolt(3) }
            25 -> resize(12, 0.14f, true)
            28 -> resize(5, 0.12f, true)
            32 -> resize(3, 0.12f, true)
            36 -> resize(5, 0.12f, true)
            38 -> resize(3, 0.12f, true)
            40 -> { resize(3, 0.2f, true); board.cubeTarget = 1f }
            44 -> vfx.jolt(5)
            48 -> vfx.big(1f)
            52 -> { board.cubeTarget = 0f; resize(5, 0.12f, true) }
            56 -> resize(12, 0.12f, true)
            58 -> resize(7, 0.12f, true)
            60 -> resize(3, 0.12f, true)
            62 -> { vfx.big(1f); styleFor(0) }
        }
        if (bar > 0 && bar % 8 == 0 && bar != 8 && bar != 16 && bar != 32 && bar != 40 && bar != 48 && bar != 56) vfx.big(0.7f)
    }

    private fun styleFor(sec: Int) {
        val w = vfx.warp
        fun set(tw: Float, wv: Float, sh: Float, yw: Float, pt: Float, rl: Float) {
            w.twist.t = tw; w.wave.t = wv; w.shear.t = sh; w.yaw.t = yw; w.pitch.t = pt; w.roll.t = rl
        }
        when (sec) {
            0 -> set(0f, 0f, 0f, 0f, 0f, 0f)
            1 -> set(0f, 0f, 0f, 0f, 0.35f, 0f)
            2 -> set(0.25f, 0.08f, 0f, -0.3f, 0.2f, 0f)
            3 -> set(-0.2f, 0f, 0.25f, 0.5f, -0.4f, 0f)
            4 -> set(-0.5f, 0.12f, 0f, 0f, 0.5f, 0.15f)
            5 -> set(0.1f, 0f, 0f, 0.7f, 0.4f, 0f)
            6 -> set(0.6f, 0.15f, -0.3f, -0.6f, -0.3f, -0.2f)
            else -> set(-0.8f, 0.2f, 0f, 0.9f, 0.6f, 0.3f)
        }
    }
}
