package com.crackcore

import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

class LineBatch(cap: Int) {
    private var buf = FloatArray(cap * 4)
    var n = 0

    fun add(x1: Float, y1: Float, x2: Float, y2: Float) {
        val i = n * 4
        if (i + 4 > buf.size) buf = buf.copyOf(buf.size * 2)
        buf[i] = x1; buf[i + 1] = y1; buf[i + 2] = x2; buf[i + 3] = y2
        n++
    }

    fun flush(c: Canvas, p: Paint) {
        if (n > 0) c.drawLines(buf, 0, n * 4, p)
        n = 0
    }
}

class Renderer {
    companion object {
        const val APPROACH = 0.85f
        private val AX = floatArrayOf(0f, 0.9f, 0.38f, 0.38f, -0.38f, -0.38f, -0.9f)
        private val AY = floatArrayOf(-1f, 0f, 0f, 0.9f, 0.9f, 0f, 0f)
    }

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val invPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        if (Build.VERSION.SDK_INT >= 29) blendMode = BlendMode.DIFFERENCE
    }
    private val path = Path()
    private val mainB = LineBatch(1024)
    private val faintB = LineBatch(1024)
    private val outB = LineBatch(256)
    private val symB = LineBatch(512)
    private val teleB = LineBatch(64)
    private val curB = LineBatch(64)
    private val bgB = LineBatch(1024)
    private val tmp2 = FloatArray(2)
    private val tpts = FloatArray(32)
    var uiStart = 0

    fun reset() {
        uiStart = 0
    }

    private fun flushB(c: Canvas, b: LineBatch, width: Float, alpha: Int) {
        line.strokeWidth = width
        line.alpha = alpha
        b.flush(c, line)
    }

    // ---------------------------------------------------------------- фон
    fun drawTunnel(c: Canvas, cx: Float, cy: Float, size: Float, t: Float, count: Int, alpha: Float, amp: Float, u: Float) {
        val cxs = floatArrayOf(-1f, 1f, 1f, -1f, -1f)
        val cys = floatArrayOf(-1f, -1f, 1f, 1f, -1f)
        line.color = Color.WHITE
        for (i in 0 until count) {
            val f = (i.toFloat() / count + t * 0.07f) % 1f
            val s = size * (0.04f + f * f * 1.5f)
            val a = sin(f * 3.14159f) * alpha
            if (a < 0.02f) continue
            val ang = t * 0.15f + f * 1.7f * sin(t * 0.2f + 1f) + i * 0.4f
            val ca = cos(ang)
            val sa = sin(ang)
            var px = 0f
            var py = 0f
            var first = true
            for (j in 0..24) {
                val e = min(3, j / 6)
                val k = (j - e * 6) / 6f
                val x = (cxs[e] + (cxs[e + 1] - cxs[e]) * k) * s
                val y = (cys[e] + (cys[e + 1] - cys[e]) * k) * s
                val d = amp * sin(j * 0.9f + t * 3f + i)
                val xx = x + d
                val yy = y + d * 0.7f
                val rx = cx + xx * ca - yy * sa
                val ry = cy + xx * sa + yy * ca
                if (!first) bgB.add(px, py, rx, ry)
                px = rx; py = ry; first = false
            }
            flushB(c, bgB, (1f + 2f * f) * u, (a * 255f).toInt().coerceIn(0, 255))
        }
    }

    fun drawTesseract(c: Canvas, cx: Float, cy: Float, size: Float, a1: Float, a2: Float, a3: Float, color: Int, alpha: Int, width: Float) {
        val px = FloatArray(16)
        val py = FloatArray(16)
        val c1 = cos(a1); val s1 = sin(a1)
        val c2 = cos(a2); val s2 = sin(a2)
        val c3 = cos(a3); val s3 = sin(a3)
        for (i in 0 until 16) {
            val x = if ((i and 1) != 0) 1f else -1f
            val y = if ((i and 2) != 0) 1f else -1f
            val z = if ((i and 4) != 0) 1f else -1f
            val w = if ((i and 8) != 0) 1f else -1f
            val x1 = x * c1 - w * s1
            val w1 = x * s1 + w * c1
            val y2 = y * c2 - z * s2
            val z2 = y * s2 + z * c2
            val z3 = z2 * c3 - w1 * s3
            val w3 = z2 * s3 + w1 * c3
            val k4 = 1f / (2.6f - w3)
            val k3 = 1.6f / (2.6f - z3 * k4)
            px[i] = cx + x1 * k4 * k3 * size
            py[i] = cy + y2 * k4 * k3 * size
        }
        for (a in 0 until 16) for (bit in 0 until 4) {
            val b = a xor (1 shl bit)
            if (a < b) bgB.add(px[a], py[a], px[b], py[b])
        }
        line.color = color
        flushB(c, bgB, width, alpha)
        line.color = Color.WHITE
    }

    private fun sier(b: LineBatch, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float, d: Int) {
        if (d == 0) {
            b.add(x1, y1, x2, y2); b.add(x2, y2, x3, y3); b.add(x3, y3, x1, y1)
            return
        }
        val ax = (x1 + x2) / 2; val ay = (y1 + y2) / 2
        val bx = (x2 + x3) / 2; val by = (y2 + y3) / 2
        val cx = (x1 + x3) / 2; val cy = (y1 + y3) / 2
        sier(b, x1, y1, ax, ay, cx, cy, d - 1)
        sier(b, ax, ay, x2, y2, bx, by, d - 1)
        sier(b, cx, cy, bx, by, x3, y3, d - 1)
    }

    fun drawSierpinski(c: Canvas, cx: Float, cy: Float, r: Float, rot: Float, depth: Int, alpha: Int, width: Float) {
        val x = FloatArray(3)
        val y = FloatArray(3)
        for (k in 0 until 3) {
            val a = rot + k * 2.0944f
            x[k] = cx + cos(a) * r
            y[k] = cy + sin(a) * r
        }
        sier(bgB, x[0], y[0], x[1], y[1], x[2], y[2], depth)
        flushB(c, bgB, width, alpha)
    }

    fun drawBackdrop(c: Canvas, cx: Float, cy: Float, size: Float, t: Float, energy: Float, strength: Float, u: Float, fractal: Boolean) {
        drawTunnel(c, cx, cy, size, t, 12, 0.35f * strength, 6f * u * (1f + energy * 3f), u)
        drawTesseract(
            c, cx, cy, size * 0.75f * (1f + energy * 0.08f), t * 0.31f, t * 0.23f, t * 0.17f,
            Color.WHITE, (70 * strength + 60 * energy).toInt().coerceIn(0, 200), 1.6f * u
        )
        if (fractal) drawSierpinski(c, cx, cy, size * 0.9f, t * 0.2f, 4, (50 + 60 * energy).toInt(), 1.2f * u)
    }

    // ---------------------------------------------------------------- поле
    private fun seg3(bt: LineBatch, wp: Warp, ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, n: Int) {
        wp.map(ax, ay, az, tmp2, 0)
        var px = tmp2[0]
        var py = tmp2[1]
        for (k in 1..n) {
            val f = k.toFloat() / n
            wp.map(ax + (bx - ax) * f, ay + (by - ay) * f, az + (bz - az) * f, tmp2, 0)
            bt.add(px, py, tmp2[0], tmp2[1])
            px = tmp2[0]; py = tmp2[1]
        }
    }

    private fun symbol(bt: LineBatch, wp: Warp, who: Int, x: Float, y: Float, z: Float, s: Float) {
        if (who == 1) {
            val r = 0.28f * s
            seg3(bt, wp, x - r, y - r, z, x + r, y + r, z, 6)
            seg3(bt, wp, x + r, y - r, z, x - r, y + r, z, 6)
        } else {
            val r = 0.32f * s
            wp.map(x + r, y, z, tmp2, 0)
            var px = tmp2[0]
            var py = tmp2[1]
            for (k in 1..20) {
                val a = k * 0.31416f
                wp.map(x + r * cos(a), y + r * sin(a), z, tmp2, 0)
                bt.add(px, py, tmp2[0], tmp2[1])
                px = tmp2[0]; py = tmp2[1]
            }
        }
    }

    private fun inRangeF(v: Float, lo: Float, n: Float) = v >= lo - 0.01f && v <= lo + n + 0.01f

    private fun easeBack(x: Float): Float {
        val c1 = 1.70158f
        val c3 = c1 + 1f
        val q = x - 1f
        return 1f + c3 * q * q * q + c1 * q * q
    }

    fun drawBoard(c: Canvas, w: World, t: Float) {
        val b = w.board
        val wp = w.vfx.warp
        val u = w.layout.u
        val vN = b.vN
        val half = vN / 2f
        val depth = b.cube * vN
        val zf = -depth / 2f
        val segs = if (w.vfx.quality < 0.6f) 10 else 18
        val layers = if (depth > 0.05f) (if (vN <= 5.5f) 4 else 2) else 1
        val iMin = floor(min(b.fromLo, b.toLo)).toInt()
        val iMax = ceil(max(b.fromLo + b.fromN, b.toLo + b.toN)).toInt()

        for (l in 0 until layers) {
            val z = if (layers == 1) zf else zf + depth * l / (layers - 1)
            for (i in iMin..iMax) {
                val inNew = inRangeF(i.toFloat(), b.toLo, b.toN)
                val inOld = inRangeF(i.toFloat(), b.fromLo, b.fromN)
                val f = if (inNew && inOld) 1f else if (inNew) b.ease else if (inOld) 1f - b.ease else 0f
                if (f <= 0.02f) continue
                val uu = i - b.vLo - half
                val bt = if (f >= 0.98f && l == 0) mainB else faintB
                seg3(bt, wp, uu, -half, z, uu, half, z, segs)
                seg3(bt, wp, -half, uu, z, half, uu, z, segs)
            }
            if (l == 0 || l == layers - 1) {
                seg3(outB, wp, -half, -half, z, half, -half, z, segs)
                seg3(outB, wp, half, -half, z, half, half, z, segs)
                seg3(outB, wp, half, half, z, -half, half, z, segs)
                seg3(outB, wp, -half, half, z, -half, -half, z, segs)
            }
        }
        if (depth > 0.05f) {
            val nn = vN.roundToInt()
            val all = vN <= 5.5f
            for (a in 0..nn) for (bb in 0..nn) {
                if (!all && !((a == 0 || a == nn) && (bb == 0 || bb == nn))) continue
                seg3(faintB, wp, a - half, bb - half, zf, a - half, bb - half, zf + depth, 4)
            }
        }
        flushB(c, faintB, 1.4f * u, 80)
        flushB(c, mainB, 2.2f * u, 190)
        flushB(c, outB, 4.2f * u, 255)

        // символы
        val cc = b.vLo + (vN - 1f) / 2f
        val sw = max(2f * u, wp.cell * 0.085f)
        for ((key, who) in b.cells) {
            val x = BoardSystem.kx(key) - cc
            val y = BoardSystem.ky(key) - cc
            val age = w.time - (b.birth[key] ?: -9f)
            val s = if (age < 0.28f) easeBack(max(0f, age) / 0.28f) else 1f
            symbol(symB, wp, who, x, y, zf, s)
            if (depth > 0.05f) {
                symbol(symB, wp, who, x, y, zf + depth, s)
                val r = (if (who == 1) 0.28f else 0.32f) * s
                if (who == 1) {
                    seg3(symB, wp, x - r, y - r, zf, x - r, y - r, zf + depth, 3)
                    seg3(symB, wp, x + r, y - r, zf, x + r, y - r, zf + depth, 3)
                    seg3(symB, wp, x - r, y + r, zf, x - r, y + r, zf + depth, 3)
                    seg3(symB, wp, x + r, y + r, zf, x + r, y + r, zf + depth, 3)
                } else {
                    seg3(symB, wp, x + r, y, zf, x + r, y, zf + depth, 3)
                    seg3(symB, wp, x - r, y, zf, x - r, y, zf + depth, 3)
                    seg3(symB, wp, x, y + r, zf, x, y + r, zf + depth, 3)
                    seg3(symB, wp, x, y - r, zf, x, y - r, zf + depth, 3)
                }
            }
            if (age in 0f..0.2f) {
                path.reset()
                wp.map(x - 0.5f, y - 0.5f, zf, tmp2, 0); path.moveTo(tmp2[0], tmp2[1])
                wp.map(x + 0.5f, y - 0.5f, zf, tmp2, 0); path.lineTo(tmp2[0], tmp2[1])
                wp.map(x + 0.5f, y + 0.5f, zf, tmp2, 0); path.lineTo(tmp2[0], tmp2[1])
                wp.map(x - 0.5f, y + 0.5f, zf, tmp2, 0); path.lineTo(tmp2[0], tmp2[1])
                path.close()
                fill.color = Color.WHITE
                fill.alpha = ((1f - age / 0.2f) * 220f).toInt().coerceIn(0, 255)
                c.drawPath(path, fill)
            }
        }
        flushB(c, symB, sw, 255)

        // телеграф хода ИИ (мигающий призрак O)
        if (w.aiPending && ((t * 14f).toInt() and 1) == 0) {
            symbol(teleB, wp, 2, w.aiTx - cc, w.aiTy - cc, zf, 1.1f)
            flushB(c, teleB, 2.6f * u, 200)
        }

        // курсор
        val kx = b.cx - cc
        val ky = b.cy - cc
        seg3(curB, wp, kx - 0.5f, ky - 0.5f, zf, kx + 0.5f, ky - 0.5f, zf, 4)
        seg3(curB, wp, kx + 0.5f, ky - 0.5f, zf, kx + 0.5f, ky + 0.5f, zf, 4)
        seg3(curB, wp, kx + 0.5f, ky + 0.5f, zf, kx - 0.5f, ky + 0.5f, zf, 4)
        seg3(curB, wp, kx - 0.5f, ky + 0.5f, zf, kx - 0.5f, ky - 0.5f, zf, 4)
        flushB(c, curB, 4.5f * u, (185 + 70 * sin(t * 16f)).toInt().coerceIn(0, 255))
        line.color = Color.WHITE
    }

    fun drawScene(c: Canvas, w: World, W: Float, H: Float) {
        val v = w.vfx
        val L = w.layout
        val u = L.u
        val t = w.time
        c.drawColor(Color.BLACK)
        c.save()
        val z = 1f + v.zoom + v.pulse * 0.01f
        val sh = v.shake * 16f * u
        c.translate(W / 2f + sin(t * 91f) * sh, H / 2f + cos(t * 77f) * sh)
        c.scale(z, z)
        c.translate(-W / 2f, -H / 2f)
        drawBackdrop(c, L.bx, L.by, min(W, H) * 0.5f, t, v.pulse, 0.5f + 0.1f * w.section, u, w.section >= 4)
        drawBoard(c, w, t)
        v.draw(c, u)
        c.restore()
    }

    fun drawOverlay(c: Canvas, v: Vfx, W: Float, H: Float) {
        if (v.flash > 0.01f) {
            fill.color = Color.WHITE
            fill.alpha = (min(1f, v.flash) * 255f).toInt()
            c.drawRect(0f, 0f, W, H, fill)
        }
        if (v.invert > 0.02f && Build.VERSION.SDK_INT >= 29) c.drawRect(0f, 0f, W, H, invPaint)
    }

    fun drawBand(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float) {
        if (Build.VERSION.SDK_INT >= 29) c.drawRect(x0, y0, x1, y1, invPaint)
    }

    // ---------------------------------------------------------------- интерфейс
    private fun arrow(p: Path, cx: Float, cy: Float, r: Float, lane: Int) {
        val th = when (lane) { 0 -> -1.5708f; 1 -> 3.14159f; 2 -> 0f; else -> 1.5708f }
        val ct = cos(th)
        val st = sin(th)
        p.reset()
        for (k in 0 until 7) {
            val rx = AX[k] * ct - AY[k] * st
            val ry = AX[k] * st + AY[k] * ct
            if (k == 0) p.moveTo(cx + rx * r, cy + ry * r) else p.lineTo(cx + rx * r, cy + ry * r)
        }
        p.close()
    }

    fun drawUi(c: Canvas, w: World) {
        val L = w.layout
        val u = L.u
        val t = w.songT.toFloat()
        line.color = Color.WHITE
        // дорожки
        for (i in 0 until 4) {
            line.alpha = 40
            line.strokeWidth = 2f * u
            c.drawLine(L.laneX(i), L.laneTop, L.laneX(i), L.hitY, line)
        }
        val speed = (L.hitY - L.laneTop) / APPROACH
        // зона попадания
        for (i in 0 until 4) {
            arrow(path, L.laneX(i), L.hitY, L.btnW * 0.27f, i)
            line.strokeWidth = 3f * u
            line.alpha = (110 + w.btn[i] * 145f).toInt().coerceIn(0, 255)
            c.drawPath(path, line)
        }
        // ноты
        val notes = w.rhythm.notes
        while (uiStart < notes.size && notes[uiStart].time < t - 0.35) uiStart++
        var i = uiStart
        while (i < notes.size) {
            val n = notes[i]
            val dt = (n.time - t).toFloat()
            if (dt > APPROACH) break
            i++
            if (n.state == 1) continue
            val y = L.hitY - dt * speed
            val a = ((APPROACH - dt) / 0.18f).coerceIn(0f, 1f) * (if (n.state == 2) 0.3f else 1f)
            arrow(path, L.laneX(n.lane), y, L.btnW * 0.27f, n.lane)
            if (n.accent) {
                fill.color = Color.WHITE
                fill.alpha = (a * 255f).toInt()
                c.drawPath(path, fill)
            } else {
                fill.color = Color.BLACK
                fill.alpha = (a * 255f).toInt()
                c.drawPath(path, fill)
                line.strokeWidth = 3.5f * u
                line.alpha = (a * 255f).toInt()
                c.drawPath(path, line)
            }
        }
        fill.color = Color.WHITE
        // кнопки
        for (b in 0 until 4) {
            val p = w.btn[b]
            val left = L.margin + b * (L.btnW + L.gap)
            val inset = p * 0.04f * L.btnW
            val r = RectF(left + inset, L.btnTop + inset, left + L.btnW - inset, L.btnTop + L.btnH - inset)
            if (p > 0.01f) {
                fill.alpha = (p * 230f).toInt().coerceIn(0, 255)
                c.drawRoundRect(r, 14f * u, 14f * u, fill)
            }
            line.strokeWidth = 3f * u
            line.alpha = 255
            c.drawRoundRect(r, 14f * u, 14f * u, line)
            arrow(path, r.centerX(), r.centerY(), min(L.btnW, L.btnH) * 0.32f, b)
            fill.color = if (p > 0.5f) Color.BLACK else Color.WHITE
            fill.alpha = 255
            c.drawPath(path, fill)
            fill.color = Color.WHITE
        }
        // оценка попадания
        val j = w.judge
        if (j != null && w.judgeAge < 0.8f) {
            val a = 1f - w.judgeAge / 0.8f
            text.textSize = (46f + 16f * max(0f, 1f - w.judgeAge / 0.12f)) * u
            text.alpha = (a * 255f).toInt()
            c.drawText(j.name, L.w / 2f, L.laneTop - 30f * u, text)
        }
        // HUD: целостность, счёт, комбо
        val barW = L.w - 2 * L.margin
        val y = L.h * 0.035f
        line.strokeWidth = 2f * u
        line.alpha = 70
        c.drawLine(L.margin, y, L.margin + barW, y, line)
        line.strokeWidth = 7f * u
        line.alpha = 255
        c.drawLine(L.margin, y, L.margin + barW * w.integrity.coerceIn(0f, 1f), y, line)
        text.textSize = 34f * u
        text.alpha = 255
        c.drawText("X ${w.xWins} : ${w.oWins} O", L.w / 2f, y + 52f * u, text)
        if (w.rhythm.combo >= 4) {
            text.textAlign = Paint.Align.RIGHT
            c.drawText("x${w.rhythm.combo}", L.w - L.margin, y + 52f * u, text)
            text.textAlign = Paint.Align.CENTER
        }
        line.alpha = 255
    }

    // ---------------------------------------------------------------- меню / game over
    private fun fitText(label: String, targetW: Float): Float {
        text.textSize = 100f
        return 100f * targetW / text.measureText(label)
    }

    private fun frameButton(c: Canvas, r: RectF, label: String, anim: Float, size: Float, u: Float) {
        if (anim > 0.01f) {
            fill.color = Color.WHITE
            fill.alpha = (anim * 255f).toInt().coerceIn(0, 255)
            c.drawRect(r, fill)
        }
        line.strokeWidth = 3f * u
        line.alpha = 255
        c.drawRect(r, line)
        val k = 14f * u
        line.strokeWidth = 6f * u
        c.drawLine(r.left - k, r.top - k, r.left + k, r.top - k, line)
        c.drawLine(r.right - k, r.bottom + k, r.right + k, r.bottom + k, line)
        text.textSize = size
        text.color = if (anim > 0.5f) Color.BLACK else Color.WHITE
        c.drawText(label, r.centerX(), r.centerY() + size * 0.35f, text)
        text.color = Color.WHITE
    }

    fun drawMenuBackdrop(c: Canvas, L: Layout, v: Vfx, t: Float) {
        val u = L.u
        val cyc = 8f
        val p = (t % cyc) / cyc
        val collapse = when {
            p > 0.88f -> { val q = (p - 0.88f) / 0.12f; 1f - q * q * 0.97f }
            p < 0.08f -> { val q = p / 0.08f; 0.03f + 0.97f * (1f - (1f - q) * (1f - q)) }
            else -> 1f
        }
        c.drawColor(Color.BLACK)
        val cx = L.w / 2f
        val cy = L.h * 0.5f
        val size = max(L.w, L.h) * 0.55f * collapse
        drawBackdrop(c, cx, cy, size, t, v.pulse, 1.6f, u, true)
        // скользящие линии
        for (i in 0 until 26) {
            val y = ((i * 0.0385f + t * 0.03f * (1 + i % 3)) % 1f) * L.h
            val x0 = Pattern.rnd(i, 3) * L.w * 0.6f
            val len = (0.1f + Pattern.rnd(i, 4) * 0.5f) * L.w
            faintB.add(x0, y, x0 + len, y)
        }
        flushB(c, faintB, 1.2f * u, 60)
        v.draw(c, u)
    }

    fun drawMenuUi(c: Canvas, L: Layout, t: Float, anim: FloatArray, v: Vfx) {
        val W = L.w
        val H = L.h
        val u = L.u
        val size = fitText("CRACKCORE", W * 0.88f)
        text.textSize = size
        val ty = H * 0.30f
        fill.color = Color.BLACK
        fill.alpha = 150
        c.drawRect(0f, ty - size * 0.95f, W, ty + size * 0.4f, fill)
        text.alpha = 255
        c.drawText("CRACKCORE", W / 2f, ty, text)
        if (v.glitch > 0.1f) {
            for (k in 0 until min(2, v.sliceCount)) {
                val top = ty - size * 0.9f + v.slice[k * 4] * size * 1.2f
                c.save()
                c.clipRect(0f, top, W, top + size * 0.18f)
                c.translate(v.slice[k * 4 + 2] * 0.6f, 0f)
                c.drawText("CRACKCORE", W / 2f, ty, text)
                c.restore()
            }
        }
        val bs = W * 0.075f
        text.textSize = bs
        val pw = text.measureText("PLAY") * 0.5f + 40f * u
        L.playRect.set(W / 2f - pw, H * 0.62f - bs * 1.1f, W / 2f + pw, H * 0.62f + bs * 0.5f)
        L.exitRect.set(W / 2f - pw, H * 0.74f - bs * 1.1f, W / 2f + pw, H * 0.74f + bs * 0.5f)
        frameButton(c, L.playRect, "PLAY", anim[0], bs, u)
        frameButton(c, L.exitRect, "EXIT", anim[1], bs, u)
    }

    fun drawGameOver(c: Canvas, L: Layout, anim: Float) {
        val u = L.u
        fill.color = Color.BLACK
        fill.alpha = 140
        c.drawRect(0f, 0f, L.w, L.h, fill)
        val size = fitText("GAME OVER", L.w * 0.8f)
        text.textSize = size
        text.alpha = 255
        c.drawText("GAME OVER", L.w / 2f, L.h * 0.42f, text)
        val bs = L.w * 0.07f
        text.textSize = bs
        val pw = text.measureText("RETRY") * 0.5f + 40f * u
        L.retryRect.set(L.w / 2f - pw, L.h * 0.58f - bs * 1.1f, L.w / 2f + pw, L.h * 0.58f + bs * 0.5f)
        frameButton(c, L.retryRect, "RETRY", anim, bs, u)
    }

    fun absf(x: Float) = abs(x)
}
