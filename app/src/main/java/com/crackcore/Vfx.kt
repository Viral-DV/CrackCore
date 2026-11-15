package com.crackcore

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class Layout {
    var w = 1080f
    var h = 1920f
    var u = 1f
    var bx = 540f
    var by = 800f
    var boardPx = 900f
    var laneTop = 1200f
    var hitY = 1550f
    var btnTop = 1650f
    var btnH = 200f
    var btnW = 230f
    var margin = 40f
    var gap = 20f
    val playRect = android.graphics.RectF()
    val exitRect = android.graphics.RectF()
    val retryRect = android.graphics.RectF()

    fun compute(W: Float, H: Float) {
        w = W
        h = H
        u = W / 1080f
        margin = W * 0.04f
        gap = W * 0.02f
        btnW = (W - 2 * margin - 3 * gap) / 4f
        btnH = min(btnW * 0.85f, H * 0.12f)
        val bottom = H * 0.03f
        btnTop = H - bottom - btnH
        hitY = btnTop - btnW * 0.42f
        laneTop = min(H * 0.60f, hitY - btnW * 2.2f)
        boardPx = min(W * 0.90f, (laneTop - H * 0.08f) * 0.96f)
        bx = W / 2f
        by = (H * 0.07f + laneTop) / 2f
    }

    fun laneX(i: Int) = margin + btnW * 0.5f + i * (btnW + gap)
}

class Spring(var v: Float = 0f, var t: Float = 0f, private val k: Float = 50f, private val c: Float = 7f) {
    var vel = 0f
    fun update(dt: Float) {
        vel += (k * (t - v) - c * vel) * dt
        v += vel * dt
    }

    fun kick(i: Float) {
        vel += i
    }
}

class Shock(var r: Float, val speed: Float, val amp: Float, val width: Float, var life: Float, val maxLife: Float)

/** Нелинейное отображение мира (клетки) в экран: скручивание, волны, сдвиг, ударные волны, 3D-поворот, перспектива. */
class Warp {
    val twist = Spring(0f, 0f, 30f, 5f)
    val shear = Spring(0f, 0f, 50f, 7f)
    val yaw = Spring(0f, 0f, 20f, 4f)
    val pitch = Spring(0f, 0f, 20f, 4f)
    val roll = Spring(0f, 0f, 30f, 5f)
    val wave = Spring(0f, 0f, 60f, 6f)
    private val springs = arrayOf(twist, shear, yaw, pitch, roll, wave)
    var wavePhase = 0f
    var waveFreq = 1.3f
    val shocks = ArrayList<Shock>()
    var cx = 0f
    var cy = 0f
    var cell = 100f
    var fov = 12f
    var span = 3f
    private var cY = 1f
    private var sY = 0f
    private var cP = 1f
    private var sP = 0f
    private var cR = 1f
    private var sR = 0f

    fun update(dt: Float) {
        for (s in springs) {
            s.update(dt * 0.5f)
            s.update(dt * 0.5f)
        }
        var i = 0
        while (i < shocks.size) {
            val s = shocks[i]
            s.r += s.speed * dt
            s.life -= dt
            if (s.life <= 0f) shocks.removeAt(i) else i++
        }
        prepare()
    }

    fun prepare() {
        cY = cos(yaw.v); sY = sin(yaw.v)
        cP = cos(pitch.v); sP = sin(pitch.v)
        cR = cos(roll.v); sR = sin(roll.v)
    }

    fun map(x0: Float, y0: Float, z0: Float, out: FloatArray, i: Int) {
        var x = x0
        var y = y0
        val z = z0
        val tw = twist.v
        if (tw > 1e-4f || tw < -1e-4f) {
            val r = sqrt(x * x + y * y)
            val a = tw * exp(-r / span)
            val ca = cos(a)
            val sa = sin(a)
            val nx = x * ca - y * sa
            y = x * sa + y * ca
            x = nx
        }
        val wv = wave.v
        if (wv > 1e-4f || wv < -1e-4f) {
            x += wv * sin(y * waveFreq + wavePhase)
            y += wv * sin(x * waveFreq * 0.8f + wavePhase * 1.3f)
        }
        x += shear.v * y
        for (s in shocks) {
            val rr = sqrt(x * x + y * y)
            if (rr > 1e-4f) {
                val d = (rr - s.r) / s.width
                val k = s.amp * exp(-d * d) * (s.life / s.maxLife)
                x += x / rr * k
                y += y / rr * k
            }
        }
        val x1 = x * cY + z * sY
        val z1 = -x * sY + z * cY
        val y1 = y * cP - z1 * sP
        val z2 = y * sP + z1 * cP
        val x2 = x1 * cR - y1 * sR
        val y2 = x1 * sR + y1 * cR
        val p = fov / max(1f, fov + z2)
        out[i] = cx + x2 * p * cell
        out[i + 1] = cy + y2 * p * cell
    }
}

class ParticleSystem(private val cap: Int) {
    private val px = FloatArray(cap)
    private val py = FloatArray(cap)
    private val vx = FloatArray(cap)
    private val vy = FloatArray(cap)
    private val life = FloatArray(cap)
    private val maxLife = FloatArray(cap)
    private val size = FloatArray(cap)
    private val ang = FloatArray(cap)
    private val spin = FloatArray(cap)
    private val kind = ByteArray(cap)
    var count = 0
    var limit = cap
    var curl = 0f
    var pull = 0f
    var drag = 0f
    var cx = 0f
    var cy = 0f
    private val ptBuf = Array(8) { FloatArray(cap * 2) }
    private val ptN = IntArray(8)
    private val lnBuf = Array(4) { FloatArray(cap * 4) }
    private val lnN = IntArray(4)
    private val alphas = intArrayOf(60, 120, 190, 255)
    private val paint = Paint().apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }

    /** kind: 0 точка, 1 нить по скорости, 2 вращающийся фрагмент */
    fun spawn(x: Float, y: Float, vxx: Float, vyy: Float, l: Float, sz: Float, k: Int, sp: Float = 0f, a: Float = 0f) {
        if (count >= limit || count >= cap) return
        val i = count++
        px[i] = x; py[i] = y; vx[i] = vxx; vy[i] = vyy
        life[i] = l; maxLife[i] = l; size[i] = sz
        kind[i] = k.toByte(); spin[i] = sp; ang[i] = a
    }

    private fun move(from: Int, to: Int) {
        px[to] = px[from]; py[to] = py[from]; vx[to] = vx[from]; vy[to] = vy[from]
        life[to] = life[from]; maxLife[to] = maxLife[from]; size[to] = size[from]
        ang[to] = ang[from]; spin[to] = spin[from]; kind[to] = kind[from]
    }

    fun update(dt: Float) {
        var i = 0
        while (i < count) {
            life[i] -= dt
            if (life[i] <= 0f) {
                move(count - 1, i)
                count--
                continue
            }
            if (curl != 0f || pull != 0f) {
                val dx = px[i] - cx
                val dy = py[i] - cy
                vx[i] += (-dy * curl - dx * pull) * dt
                vy[i] += (dx * curl - dy * pull) * dt
            }
            if (drag != 0f) {
                val f = max(0f, 1f - drag * dt)
                vx[i] *= f
                vy[i] *= f
            }
            ang[i] += spin[i] * dt
            px[i] += vx[i] * dt
            py[i] += vy[i] * dt
            i++
        }
    }

    fun draw(c: Canvas, u: Float, color: Int) {
        java.util.Arrays.fill(ptN, 0)
        java.util.Arrays.fill(lnN, 0)
        for (i in 0 until count) {
            val a = life[i] / maxLife[i]
            val b = min(3, (a * 4f).toInt())
            when (kind[i].toInt()) {
                0 -> {
                    val bi = b + (if (size[i] > 2.5f) 4 else 0)
                    val n = ptN[bi]
                    ptBuf[bi][n] = px[i]
                    ptBuf[bi][n + 1] = py[i]
                    ptN[bi] = n + 2
                }
                1 -> {
                    val n = lnN[b]
                    val buf = lnBuf[b]
                    buf[n] = px[i]; buf[n + 1] = py[i]
                    buf[n + 2] = px[i] - vx[i] * 0.045f; buf[n + 3] = py[i] - vy[i] * 0.045f
                    lnN[b] = n + 4
                }
                else -> {
                    val h = size[i] * 0.5f
                    val dx = cos(ang[i]) * h
                    val dy = sin(ang[i]) * h
                    val n = lnN[b]
                    val buf = lnBuf[b]
                    buf[n] = px[i] - dx; buf[n + 1] = py[i] - dy
                    buf[n + 2] = px[i] + dx; buf[n + 3] = py[i] + dy
                    lnN[b] = n + 4
                }
            }
        }
        for (b in 0 until 4) {
            paint.color = color
            paint.alpha = alphas[b]
            if (ptN[b] > 0) {
                paint.strokeWidth = 2.4f * u
                c.drawPoints(ptBuf[b], 0, ptN[b], paint)
            }
            if (ptN[b + 4] > 0) {
                paint.strokeWidth = 5f * u
                c.drawPoints(ptBuf[b + 4], 0, ptN[b + 4], paint)
            }
            if (lnN[b] > 0) {
                paint.strokeWidth = 1.4f * u
                c.drawLines(lnBuf[b], 0, lnN[b], paint)
            }
        }
    }
}

class Ring(var x: Float, var y: Float, var r: Float, val v: Float, var life: Float, val maxLife: Float, val width: Float)

class Vfx {
    val warp = Warp()
    val ps = ParticleSystem(6000)
    val rings = ArrayList<Ring>()
    var quality = 1f
    var pulse = 0f
    var zoom = 0f
    var flash = 0f
    var glitch = 0f
    var invert = 0f
    var shake = 0f
    var sliceCount = 0
    val slice = FloatArray(6 * 4) // y0, y1 (0..1), dx, invert
    var ambient = 0
    var W = 1080f
    var H = 1920f
    var u = 1f
    var bx = 540f
    var by = 800f
    private val rnd = java.util.Random(7)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }

    fun setLayout(l: Layout) {
        W = l.w; H = l.h; u = l.u; bx = l.bx; by = l.by
        ps.cx = bx; ps.cy = by
    }

    private fun r(a: Float, b: Float) = a + (b - a) * rnd.nextFloat()
    private fun sgn() = if (rnd.nextBoolean()) 1f else -1f
    private fun n(k: Int) = max(1, (k * quality).toInt())

    fun newSlices(strength: Float) {
        sliceCount = min(6, 2 + (strength * 4).toInt())
        for (i in 0 until sliceCount) {
            val y0 = rnd.nextFloat() * 0.9f
            slice[i * 4] = y0
            slice[i * 4 + 1] = min(1f, y0 + r(0.015f, 0.1f))
            slice[i * 4 + 2] = sgn() * r(10f, 70f) * u * (0.5f + strength)
            slice[i * 4 + 3] = if (rnd.nextFloat() < 0.3f) 1f else 0f
        }
    }

    fun doGlitch(s: Float) {
        glitch = max(glitch, s)
        newSlices(s)
    }

    fun burst(x: Float, y: Float, count: Int, speed: Float, life: Float, fragments: Boolean) {
        for (i in 0 until n(count)) {
            val a = r(0f, 6.2832f)
            val sp = speed * r(0.2f, 1f)
            val k = if (fragments && rnd.nextFloat() < 0.5f) 2 else if (rnd.nextFloat() < 0.4f) 1 else 0
            val sz = if (k == 0) r(1f, 4f) else r(8f, 30f) * u
            ps.spawn(x, y, cos(a) * sp, sin(a) * sp, r(life * 0.5f, life), sz, k, r(-8f, 8f), r(0f, 6.28f))
        }
    }

    /** Сборка объекта из частиц: частицы летят из кольца в центр. */
    fun gather(x: Float, y: Float, count: Int, radius: Float, life: Float) {
        for (i in 0 until n(count)) {
            val a = r(0f, 6.2832f)
            val rr = radius * r(0.6f, 1.2f)
            ps.spawn(x + cos(a) * rr, y + sin(a) * rr, -cos(a) * rr / life, -sin(a) * rr / life, life, r(1f, 4f), 0)
        }
    }

    fun addShock(s: Shock) {
        if (warp.shocks.size > 5) warp.shocks.removeAt(0)
        warp.shocks.add(s)
    }

    fun kick(i: Float, sec: Int) {
        pulse = max(pulse, i)
        zoom += 0.010f * i
        warp.wave.kick(2.5f * i)
        addShock(Shock(0.3f, 8f, 0.28f * i, 1.1f, 0.8f, 0.8f))
        if (sec >= 1) {
            for (j in 0 until n((10 * i).toInt())) {
                val a = r(0f, 6.28f)
                val sp = r(500f, 1600f) * u
                ps.spawn(bx, by, cos(a) * sp, sin(a) * sp, r(0.25f, 0.5f), 1f, 1)
            }
        }
        if (sec >= 2 && i > 0.9f && rnd.nextFloat() < 0.35f) doGlitch(0.35f)
        if (sec >= 4) flash = max(flash, 0.10f * i)
        shake = max(shake, 0.18f * i * (1f + sec * 0.1f))
    }

    fun snare(a: Float, sec: Int) {
        doGlitch(0.4f + 0.5f * a)
        if (sec >= 3 && a > 0.9f && rnd.nextFloat() < 0.35f) invert = max(invert, 0.07f)
        for (j in 0 until n((8 * a).toInt())) {
            ps.spawn(r(0f, W), r(0f, H * 0.6f), sgn() * r(900f, 2200f) * u, 0f, r(0.2f, 0.4f), r(20f, 80f) * u, 2, 0f, 0f)
        }
    }

    fun hat(sec: Int, a: Float) {
        for (j in 0 until n(1 + (a * 2f).toInt())) {
            ps.spawn(r(0f, W), r(0f, H), sgn() * r(1200f, 2600f) * u, 0f, r(0.15f, 0.3f), 1f, 1)
        }
    }

    fun big(s: Float) {
        flash = max(flash, 0.55f * s + 0.2f)
        doGlitch(1f)
        invert = max(invert, 0.05f + 0.07f * s)
        shake = max(shake, 0.7f * s)
        zoom += 0.05f * s
        addShock(Shock(0.5f, 14f, 0.8f * s, 1.6f, 1.1f, 1.1f))
        rings.add(Ring(bx, by, 20f * u, 1400f * u, 0.9f, 0.9f, 4f * u))
        burst(bx, by, (180 * s).toInt(), 1500f * u, 1f, true)
    }

    fun jolt(sec: Int) {
        val k = 1f + sec * 0.15f
        warp.twist.kick(sgn() * r(5f, 10f) * k)
        warp.yaw.kick(sgn() * r(1.5f, 4f))
        warp.pitch.kick(sgn() * r(1f, 3f))
        warp.shear.kick(sgn() * r(1f, 3f))
        warp.roll.kick(sgn() * r(0.5f, 2f))
        warp.wave.kick(sgn() * r(1.5f, 4f))
        doGlitch(0.6f)
    }

    fun placeFx(x: Float, y: Float, who: Int, q: Float) {
        pulse = max(pulse, 0.5f + 0.5f * q)
        rings.add(Ring(x, y, 10f * u, 600f * u * (0.6f + q), 0.5f, 0.5f, 3f * u))
        if (who == 1) {
            gather(x, y, (10 + 30 * q).toInt(), 140f * u, 0.28f)
            burst(x, y, (12 + 20 * q).toInt(), 500f * u, 0.5f, false)
            if (q > 0.6f) addShock(Shock(0.5f, 7f, 0.25f * q, 1f, 0.6f, 0.6f))
        } else {
            burst(x, y, 40, 900f * u, 0.7f, true)
            invert = max(invert, 0.06f)
            doGlitch(0.4f)
        }
    }

    fun hitFx(x: Float, y: Float, j: Judge, accent: Boolean) {
        rings.add(Ring(x, y, 20f * u, 300f * u, 0.3f, 0.3f, 2.5f * u))
        val cnt = when (j) { Judge.PERFECT -> 24; Judge.GREAT -> 14; else -> 6 }
        for (i in 0 until n(cnt)) {
            ps.spawn(x + r(-40f, 40f) * u, y, r(-120f, 120f) * u, -r(200f, 800f) * u, r(0.2f, 0.5f), 1f, 1)
        }
        if (accent) pulse = max(pulse, 0.35f)
    }

    fun missFx() {
        doGlitch(0.7f)
        shake = max(shake, 0.5f)
        invert = max(invert, 0.05f)
    }

    fun conflictFx() {
        doGlitch(0.9f)
        invert = max(invert, 0.08f)
        shake = max(shake, 0.4f)
    }

    fun update(dt: Float) {
        pulse *= exp(-dt * 7f)
        zoom *= exp(-dt * 9f)
        flash *= exp(-dt * 5f)
        shake *= exp(-dt * 8f)
        glitch = max(0f, glitch - dt * 3.5f)
        invert = max(0f, invert - dt * 9f)
        if (glitch <= 0f) sliceCount = 0
        warp.wavePhase += dt * (3f + pulse * 8f)
        warp.update(dt)
        var i = 0
        while (i < rings.size) {
            val g = rings[i]
            g.life -= dt
            g.r += g.v * dt
            if (g.life <= 0f) rings.removeAt(i) else i++
        }
        ps.limit = max(800, (6000 * quality).toInt())
        ps.curl = 0.55f
        ps.pull = 0.10f
        ps.drag = 0.35f
        if (ambient > 0) {
            var spawned = 0
            while (ps.count < ambient * quality && spawned < 60) {
                ps.spawn(r(0f, W), r(0f, H), r(-40f, 40f) * u, r(-40f, 40f) * u, r(3f, 6f), r(1f, 4f), 0)
                spawned++
            }
        }
        ps.update(dt)
    }

    fun draw(c: Canvas, u: Float) {
        for (g in rings) {
            ringPaint.strokeWidth = g.width
            ringPaint.alpha = (255f * g.life / g.maxLife).toInt().coerceIn(0, 255)
            c.drawCircle(g.x, g.y, g.r, ringPaint)
        }
        for (s in warp.shocks) {
            ringPaint.strokeWidth = 2f * u
            ringPaint.alpha = (140f * s.life / s.maxLife).toInt().coerceIn(0, 255)
            c.drawCircle(bx, by, s.r * warp.cell, ringPaint)
        }
        ps.draw(c, u, Color.WHITE)
    }
}
