package com.crackcore

import android.content.Context
import android.graphics.Canvas
import android.graphics.Picture
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.max
import kotlin.math.min

class GameView(ctx: Context, private val host: GameActivity) : SurfaceView(ctx), SurfaceHolder.Callback, Runnable {
    private enum class Scene { MENU, PLAY, FRAGMENT, GAMEOVER }

    private class Tap(val x: Float, val y: Float, val time: Long)

    private var scene = Scene.MENU
    private val layout = Layout()
    private val renderer = Renderer()
    private val menuVfx = Vfx()
    private var world: World? = null
    private var music: MusicController? = null
    private var fragment: FragmentAnimation? = null
    private val taps = ConcurrentLinkedQueue<Tap>()
    private val presses = ArrayList<Press>()
    private val picture = Picture()
    private val rnd = java.util.Random(11)

    private var thread: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var backRequested = false
    private var W = 0
    private var H = 0
    private var clock = 0f
    private var prevPhase = 0f
    private var nextGlitch = 1f
    private val menuBtn = FloatArray(3)

    // адаптивное качество и защита от перегрева
    private var ft = 0.016f
    private var adapt = 1f
    private var thermalCap = 1f
    private var qTimer = 0f
    private var thermTimer = 0f
    private var quality = 1f

    init {
        holder.addCallback(this)
        isFocusable = true
    }

    // ------------------------------------------------------------ жизненный цикл
    override fun surfaceCreated(h: SurfaceHolder) {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                h.surface.setFrameRate(max(60f, display?.refreshRate ?: 60f), Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
            } catch (e: Exception) { }
        }
        running = true
        thread = Thread(this, "crackcore-render").also { it.start() }
    }

    override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, hh: Int) {
        W = w
        H = hh
        layout.compute(w.toFloat(), hh.toFloat())
        menuVfx.setLayout(layout)
        menuVfx.ps.cx = w / 2f
        menuVfx.ps.cy = hh / 2f
        world?.vfx?.setLayout(layout)
    }

    override fun surfaceDestroyed(h: SurfaceHolder) {
        running = false
        try { thread?.join(500) } catch (e: Exception) { }
        thread = null
    }

    fun onHostPause() {
        music?.pause()
    }

    fun onHostResume() {
        music?.resume()
    }

    fun shutdown() {
        running = false
        music?.stop()
        music = null
    }

    fun onBack(): Boolean {
        if (scene == Scene.MENU) return false
        backRequested = true
        return true
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                taps.add(Tap(e.getX(i), e.getY(i), e.eventTime))
            }
        }
        return true
    }

    // ------------------------------------------------------------ цикл
    override fun run() {
        var last = System.nanoTime()
        while (running) {
            val now = System.nanoTime()
            var dt = (now - last) / 1e9f
            last = now
            if (dt > 0.05f) dt = 0.05f
            if (W > 0) {
                step(dt)
                val c: Canvas? = try {
                    holder.surface.lockHardwareCanvas()
                } catch (e: Exception) {
                    try { holder.lockCanvas() } catch (e2: Exception) { null }
                }
                if (c != null) {
                    try {
                        render(c)
                    } finally {
                        try { holder.unlockCanvasAndPost(c) } catch (e: Exception) { }
                    }
                } else {
                    try { Thread.sleep(8) } catch (e: Exception) { }
                }
            } else {
                try { Thread.sleep(8) } catch (e: Exception) { }
            }
        }
    }

    private fun adaptQuality(dt: Float) {
        ft += (dt - ft) * 0.05f
        qTimer += dt
        if (qTimer > 0.5f) {
            qTimer = 0f
            if (ft > 0.022f) adapt = max(0.4f, adapt - 0.08f)
            else if (ft < 0.0175f) adapt = min(1f, adapt + 0.03f)
        }
        thermTimer += dt
        if (thermTimer > 3f) {
            thermTimer = 0f
            if (Build.VERSION.SDK_INT >= 29) {
                try {
                    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                    val s = pm.currentThermalStatus
                    thermalCap = if (s >= 3) 0.45f else if (s == 2) 0.7f else 1f
                } catch (e: Exception) { }
            }
        }
        quality = min(adapt, thermalCap)
    }

    private fun step(dt: Float) {
        clock += dt
        adaptQuality(dt)
        if (backRequested) {
            backRequested = false
            if (scene != Scene.MENU) toMenu()
        }
        when (scene) {
            Scene.MENU -> stepMenu(dt)
            Scene.PLAY -> stepPlay(dt)
            Scene.FRAGMENT -> stepFragment(dt)
            Scene.GAMEOVER -> stepOver(dt)
        }
        for (i in menuBtn.indices) menuBtn[i] = max(0f, menuBtn[i] - dt * 5f)
    }

    private fun toMenu() {
        music?.stop()
        music = null
        world = null
        fragment = null
        scene = Scene.MENU
        taps.clear()
    }

    private fun startLevel() {
        music?.stop()
        val m = MusicController()
        m.start()
        music = m
        renderer.reset()
        world = World(m, layout)
        fragment = null
        scene = Scene.PLAY
        taps.clear()
    }

    private fun stepMenu(dt: Float) {
        menuVfx.quality = quality
        menuVfx.ambient = 700
        nextGlitch -= dt
        if (nextGlitch <= 0f) {
            menuVfx.doGlitch(0.3f + rnd.nextFloat() * 0.5f)
            if (rnd.nextFloat() < 0.3f) menuVfx.invert = 0.06f
            nextGlitch = 0.4f + rnd.nextFloat() * 2.2f
        }
        val phase = (clock % 8f)
        if (phase < prevPhase) menuVfx.big(0.8f) // схлопывание завершилось
        prevPhase = phase
        menuVfx.update(dt)
        while (true) {
            val tp = taps.poll() ?: break
            if (layout.playRect.contains(tp.x, tp.y)) {
                menuBtn[0] = 1f
                startLevel()
                return
            } else if (layout.exitRect.contains(tp.x, tp.y)) {
                menuBtn[1] = 1f
                host.exitApp()
            }
        }
    }

    private fun stepPlay(dt: Float) {
        val w = world ?: return
        val m = music ?: return
        val t = m.time()
        val now = SystemClock.uptimeMillis()
        presses.clear()
        while (true) {
            val tp = taps.poll() ?: break
            if (tp.y >= layout.hitY - layout.btnW * 0.6f) {
                val lane = (tp.x / (W / 4f)).toInt().coerceIn(0, 3)
                val delay = max(0L, now - tp.time) / 1000.0
                presses.add(Press(lane, t - delay))
            }
        }
        w.vfx.quality = quality
        w.update(t, dt, presses)
        if (w.ended == 1) {
            fragment = FragmentAnimation(layout, w, renderer)
            scene = Scene.FRAGMENT
            taps.clear()
        } else if (w.ended == 2) {
            scene = Scene.GAMEOVER
            taps.clear()
        }
    }

    private fun stepFragment(dt: Float) {
        val f = fragment ?: return
        world?.update(music?.time() ?: 0.0, dt, emptyList())
        f.update(dt)
        if (f.done && taps.isNotEmpty()) toMenu() else taps.clear()
    }

    private fun stepOver(dt: Float) {
        val w = world ?: return
        w.update(music?.time() ?: 0.0, dt, emptyList())
        while (true) {
            val tp = taps.poll() ?: break
            if (layout.retryRect.contains(tp.x, tp.y)) {
                menuBtn[2] = 1f
                startLevel()
                return
            }
        }
    }

    // ------------------------------------------------------------ рендер
    /** Глитч: сцена записывается в Picture и повторно рисуется со сдвигами отдельных полос. */
    private fun withGlitch(c: Canvas, v: Vfx, body: (Canvas) -> Unit) {
        if (v.glitch < 0.06f || v.sliceCount == 0) {
            body(c)
            return
        }
        val rc = picture.beginRecording(W, H)
        body(rc)
        picture.endRecording()
        c.drawPicture(picture)
        for (k in 0 until v.sliceCount) {
            val y0 = v.slice[k * 4] * H
            val y1 = v.slice[k * 4 + 1] * H
            c.save()
            c.clipRect(0f, y0, W.toFloat(), y1)
            c.translate(v.slice[k * 4 + 2] * min(1f, v.glitch * 1.5f), 0f)
            c.drawPicture(picture)
            if (v.slice[k * 4 + 3] > 0.5f) renderer.drawBand(c, -200f, y0, W + 200f, y1)
            c.restore()
        }
    }

    private fun render(c: Canvas) {
        val fw = W.toFloat()
        val fh = H.toFloat()
        when (scene) {
            Scene.MENU -> {
                withGlitch(c, menuVfx) { renderer.drawMenuBackdrop(it, layout, menuVfx, clock) }
                renderer.drawOverlay(c, menuVfx, fw, fh)
                renderer.drawMenuUi(c, layout, clock, menuBtn, menuVfx)
            }
            Scene.PLAY -> {
                val w = world ?: return
                withGlitch(c, w.vfx) { renderer.drawScene(it, w, fw, fh) }
                renderer.drawOverlay(c, w.vfx, fw, fh)
                renderer.drawUi(c, w)
            }
            Scene.FRAGMENT -> fragment?.draw(c)
            Scene.GAMEOVER -> {
                val w = world ?: return
                withGlitch(c, w.vfx) { renderer.drawScene(it, w, fw, fh) }
                renderer.drawOverlay(c, w.vfx, fw, fh)
                renderer.drawGameOver(c, layout, menuBtn[2])
            }
        }
    }
}
