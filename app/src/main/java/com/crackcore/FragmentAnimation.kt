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

/**
 * Фазы: 1 остановка (0-2с) -> 2 нити возникают из пустоты (2-7с) -> 3 переплетение и стягивание (7-13с)
 * -> 4 материализация, появляется цвет (13-19с) -> 5 финальный импульс (19с) и осколок остаётся висеть.
 */
class FragmentAnimation(private val L: Layout, private val world: World, private val r: Renderer) {
    companion object {
        const val N = 150
        const val P = 72
        const val T_PULSE = 19f
        const val T_DONE = 23f
    }

    var t = 0f
    val done: Boolean get() = t >= T_DONE

    private val rnd = java.util.Random(4242)
    private val loose = Array(N) { FloatArray(P * 3) }
    private val tgt = Array(N) { FloatArray(P * 3) }
    private val birth = FloatArray(N)
    private val hue = FloatArray(N)
    private val seg = FloatArray(P * 4)
    private val hx = FloatArray(N * 2)
    private val hy = FloatArray(N * 2)
    private val bridge = FloatArray(140 * 4)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fxW = ParticleSystem(2500)
    private val fxC = ParticleSystem(2500)
    private val hsv = FloatArray(3)
    private var pulsed = false

    init {
        build()
    }

    private fun rr(a: Float, b: Float) = a + (b - a) * rnd.nextFloat()
    private fun sm(x: Float): Float {
        val q = x.coerceIn(0f, 1f)
        return q * q * (3f - 2f * q)
    }

    private fun build() {
        for (i in 0 until N) {
            birth[i] = rr(2.3f, 6.8f)
            hue[i] = rr(170f, 330f)
            // свободная нить: случайный изогнутый отрезок в случайном месте пространства
            val c0 = floatArrayOf(rr(-1.9f, 1.9f), rr(-2.6f, 2.6f), rr(-1.5f, 1.5f))
            val amp = rr(0.2f, 0.65f)
            val len = rr(0.6f, 1.8f)
            var dx = rr(-1f, 1f); var dy = rr(-1f, 1f); var dz = rr(-1f, 1f)
            val dl = sqrt(dx * dx + dy * dy + dz * dz) + 1e-4f
            dx /= dl; dy /= dl; dz /= dl
            val f1 = rr(0.5f, 1.8f); val f2 = rr(0.5f, 1.8f); val f3 = rr(0.5f, 1.8f)
            val p1 = rr(0f, 6.28f); val p2 = rr(0f, 6.28f); val p3 = rr(0f, 6.28f)
            // целевая замкнутая кривая математического артефакта
            val type = i % 3
            val rx = rr(0f, 6.28f); val ry = rr(0f, 6.28f); val rz = rr(0f, 6.28f)
            val sc = rr(0.55f, 1.05f)
            val ph = rr(0f, 6.28f)
            val q = 3 + (i / 3) % 2
            for (k in 0 until P) {
                val s = k / (P - 1f)
                val o = k * 3
                loose[i][o] = c0[0] + dx * len * (s - 0.5f) + amp * sin(6.2832f * f1 * s + p1)
                loose[i][o + 1] = c0[1] + dy * len * (s - 0.5f) + amp * sin(6.2832f * f2 * s + p2)
                loose[i][o + 2] = c0[2] + dz * len * (s - 0.5f) + amp * sin(6.2832f * f3 * s + p3)

                val th = 6.2832f * s
                var x: Float
                var y: Float
                var z: Float
                when (type) {
                    0 -> {
                        val rad = 2f + cos(q * th + ph)
                        x = rad * cos(2f * th) * 0.3f
                        y = rad * sin(2f * th) * 0.3f
                        z = sin(q * th + ph) * 0.3f
                    }
                    1 -> {
                        x = cos(th)
                        y = sin(th)
                        z = 0.28f * sin(4f * th + ph)
                    }
                    else -> {
                        val lat = 1.2f * sin(3f * th + ph)
                        val lon = 2f * th
                        x = cos(lat) * cos(lon)
                        y = cos(lat) * sin(lon)
                        z = sin(lat)
                    }
                }
                // поворот Эйлера
                var y1 = y * cos(rx) - z * sin(rx)
                var z1 = y * sin(rx) + z * cos(rx)
                val x1 = x * cos(ry) + z1 * sin(ry)
                z1 = -x * sin(ry) + z1 * cos(ry)
                val x2 = x1 * cos(rz) - y1 * sin(rz)
                y1 = x1 * sin(rz) + y1 * cos(rz)
                tgt[i][o] = x2 * sc
                tgt[i][o + 1] = y1 * sc
                tgt[i][o + 2] = z1 * sc
            }
        }
    }

    fun update(dt: Float) {
        t += dt
        if (!pulsed && t >= T_PULSE) {
            pulsed = true
            val cx = L.w / 2f
            val cy = L.h * 0.47f
            val u = L.u
            for (i in 0 until 900) {
                val a = rr(0f, 6.2832f)
                val sp = rr(200f, 1400f) * u
                val k = if (rnd.nextFloat() < 0.4f) 1 else if (rnd.nextFloat() < 0.3f) 2 else 0
                fxW.spawn(cx, cy, cos(a) * sp, sin(a) * sp, rr(1.5f, 4f), if (k == 0) rr(1f, 4f) else rr(10f, 40f) * u, k, rr(-6f, 6f), rr(0f, 6.28f))
            }
            for (i in 0 until 600) {
                val a = rr(0f, 6.2832f)
                val sp = rr(100f, 900f) * u
                fxC.spawn(cx, cy, cos(a) * sp, sin(a) * sp, rr(2f, 4.5f), rr(1f, 4f), 0)
            }
        }
        for (ps in arrayOf(fxW, fxC)) {
            ps.cx = L.w / 2f
            ps.cy = L.h * 0.47f
            ps.curl = 1.2f
            ps.pull = 0.5f
            ps.drag = 0.6f
            ps.update(dt)
        }
    }

    fun draw(c: Canvas) {
        val W = L.w
        val H = L.h
        val u = L.u
        c.drawColor(Color.BLACK)

        // Фаза 1: поле замирает и растворяется
        if (t < 2.2f) {
            val a = 1f - sm(t / 1.8f)
            c.saveLayerAlpha(0f, 0f, W, H, (a * 255f).toInt().coerceIn(0, 255))
            r.drawScene(c, world, W, H)
            c.restore()
        }

        val pt = t - T_PULSE
        val cx = W / 2f
        val cy = H * 0.47f + (if (t > T_PULSE) sin(t * 1.2f) * 8f * u else 0f)
        val m = sm((t - 7f) / 6.5f)
        val form = sm((t - 13f) / 5.5f)
        val swell = if (pt > 0f) 1f + 0.25f * exp(-pt * 5f) else 1f
        val R = min(W, H) * 0.30f * (1f + 0.02f * sin(t * 1.7f)) * swell

        // реакция пространства на импульс: деформируемый туннель
        if (pt > -0.1f) {
            val a = 0.5f * exp(-max(0f, pt) * 0.6f)
            r.drawTunnel(c, cx, cy, min(W, H) * 0.6f, t, 12, a, 60f * u * exp(-max(0f, pt) * 1.2f), u)
        }

        val yaw = t * 0.33f + 0.4f
        val pitch = 0.45f * sin(t * 0.19f) + 0.25f
        val cY = cos(yaw); val sY = sin(yaw); val cP = cos(pitch); val sP = sin(pitch)
        val fov = 4f
        val ringR = max(0f, pt) * R * 3.2f
        val ringA = R * 0.35f * exp(-max(0f, pt) * 1.5f)
        var heads = 0

        for (i in 0 until N) {
            val g = sm((t - birth[i]) / 1.7f)
            if (g <= 0.002f) continue
            val gg = max(g, sm(m * 3f))
            val k0 = ((0.5f - 0.5f * gg) * (P - 1)).toInt()
            val k1 = ((0.5f + 0.5f * gg) * (P - 1)).toInt()
            if (k1 - k0 < 1) continue
            var n = 0
            var px = 0f
            var py = 0f
            for (k in k0..k1) {
                val o = k * 3
                val x = loose[i][o] + (tgt[i][o] - loose[i][o]) * m
                val y = loose[i][o + 1] + (tgt[i][o + 1] - loose[i][o + 1]) * m
                val z = loose[i][o + 2] + (tgt[i][o + 2] - loose[i][o + 2]) * m
                val x1 = x * cY + z * sY
                val z1 = -x * sY + z * cY
                val y1 = y * cP - z1 * sP
                val z2 = y * sP + z1 * cP
                val p = fov / max(1f, fov + z2)
                var sx = cx + x1 * p * R
                var sy = cy + y1 * p * R
                if (pt in 0f..3.2f) {
                    val dx = sx - cx
                    val dy = sy - cy
                    val rad = sqrt(dx * dx + dy * dy) + 1e-3f
                    val d = (rad - ringR) / (R * 0.25f)
                    val disp = ringA * exp(-d * d)
                    sx += dx / rad * disp
                    sy += dy / rad * disp
                }
                if (k > k0) {
                    seg[n] = px; seg[n + 1] = py; seg[n + 2] = sx; seg[n + 3] = sy
                    n += 4
                }
                px = sx; py = sy
                if (k == k0 && heads < N * 2) { hx[heads] = sx; hy[heads] = sy }
                if (k == k1 && heads + 1 < N * 2) { hx[heads + 1] = sx; hy[heads + 1] = sy }
            }
            heads += 2
            val a = (g * 255f).toInt()
            if (form > 0.01f) {
                hsv[0] = (hue[i] + 40f * sin(t * 0.6f + i)) % 360f
                hsv[1] = 0.15f + 0.85f * form
                hsv[2] = 1f
                line.color = Color.HSVToColor(hsv)
            } else {
                line.color = Color.WHITE
            }
            line.strokeWidth = 5f * u
            line.alpha = (a * 0.16f).toInt()
            c.drawLines(seg, 0, n, line)
            line.strokeWidth = 1.3f * u
            line.alpha = a
            c.drawLines(seg, 0, n, line)
        }

        // нити тянутся друг к другу: мосты между близкими концами
        if (m < 0.98f && heads > 0) {
            var bn = 0
            val thr = R * 0.55f
            var a = 0
            while (a < heads && bn < 140 * 4) {
                var b = a + 1
                while (b < heads && bn < 140 * 4) {
                    val dx = hx[a] - hx[b]
                    val dy = hy[a] - hy[b]
                    if (dx * dx + dy * dy < thr * thr && (a / 2) != (b / 2)) {
                        bridge[bn] = hx[a]; bridge[bn + 1] = hy[a]; bridge[bn + 2] = hx[b]; bridge[bn + 3] = hy[b]
                        bn += 4
                    }
                    b++
                }
                a++
            }
            if (bn > 0) {
                line.color = Color.WHITE
                line.strokeWidth = 1f * u
                line.alpha = (120f * (1f - m)).toInt()
                c.drawLines(bridge, 0, bn, line)
            }
        }

        // ядро: псевдо-4D артефакт проявляется и получает цвет
        if (form > 0.01f) {
            hsv[0] = 200f + 100f * sin(t * 0.5f); hsv[1] = 0.8f; hsv[2] = 1f
            val col = Color.HSVToColor(hsv)
            r.drawTesseract(c, cx, cy, R * 0.5f * form, t * 0.31f, t * 0.23f, t * 0.17f, col, (form * 255f).toInt(), 1.6f * u)
            r.drawTesseract(c, cx, cy, R * 0.28f * form, -t * 0.4f, t * 0.29f, -t * 0.21f, Color.WHITE, (form * 200f).toInt(), 1.2f * u)
            line.color = col
            line.strokeWidth = R * 0.08f * form
            line.alpha = (form * 200f).toInt()
            c.drawPoint(cx, cy, line)
        }

        // финальный импульс
        if (pt > 0f) {
            for (j in 0 until 3) {
                val rad = (pt - j * 0.12f) * R * 3.2f
                if (rad <= 0f) continue
                line.color = if (j == 1) Color.HSVToColor(floatArrayOf(280f, 0.7f, 1f)) else Color.WHITE
                line.strokeWidth = (3f - j) * u
                line.alpha = (255f * exp(-pt * 0.9f)).toInt().coerceIn(0, 255)
                c.drawCircle(cx, cy, rad, line)
            }
        }
        fxW.draw(c, u, Color.WHITE)
        hsv[0] = 280f; hsv[1] = 0.65f; hsv[2] = 1f
        fxC.draw(c, u, Color.HSVToColor(hsv))
        if (pt > 0f && pt < 0.5f) {
            line.color = Color.WHITE
            val fl = Paint().apply { color = Color.WHITE; alpha = (exp(-pt * 10f) * 200f).toInt().coerceIn(0, 255) }
            c.drawRect(0f, 0f, W, H, fl)
        }
    }
}
