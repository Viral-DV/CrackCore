package com.crackcore

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/** Единая временная сетка: 64 такта по 16 шагов, темп растёт 180 -> 222 BPM. */
object Tempo {
    const val BARS = 64
    const val STEPS = BARS * 16
    private val bpm = DoubleArray(BARS) { 180.0 + 42.0 * it / (BARS - 1) }
    val stepStart = DoubleArray(STEPS + 1)

    init {
        var t = 0.0
        for (b in 0 until BARS) {
            val sd = 60.0 / bpm[b] / 4.0
            for (s in 0 until 16) {
                stepStart[b * 16 + s] = t
                t += sd
            }
        }
        stepStart[STEPS] = t
    }

    val total: Double get() = stepStart[STEPS]

    fun stepDur(step: Int): Double {
        val i = step.coerceIn(0, STEPS - 1)
        return stepStart[i + 1] - stepStart[i]
    }

    fun stepAt(time: Double): Int {
        if (time < 0) return -1
        if (time >= total) return STEPS
        var lo = 0
        var hi = STEPS - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (stepStart[mid] <= time) lo = mid else hi = mid - 1
        }
        return lo
    }
}

/** Паттерны музыки. Используются и синтезатором, и VFX, и AI: всё в одной сетке. */
object Pattern {
    const val NO = -1000

    fun hash(a: Int, b: Int): Int {
        var h = a * 374761393 + b * 668265263 + 0x1234567
        h = (h xor (h ushr 13)) * 1274126177
        return h xor (h ushr 16)
    }

    fun rnd(a: Int, b: Int): Float = (hash(a, b) and 0xFFFF) / 65535f

    fun section(bar: Int): Int = (bar / 8).coerceIn(0, 7)

    fun kick(bar: Int, s: Int): Boolean {
        val sec = section(bar)
        if (bar % 8 == 7 && s >= 12 && sec >= 1) return s % 2 == 0
        return when (sec) {
            0 -> s == 0 || (bar >= 2 && s == 8)
            1 -> s == 0 || s == 6 || s == 10
            2, 3 -> s == 0 || s == 3 || s == 10 || (s == 6 && bar % 2 == 1) || (s == 14 && bar % 4 == 3)
            else -> s == 0 || s == 3 || s == 6 || s == 10 || (s == 13 && bar % 2 == 0)
        }
    }

    fun snare(bar: Int, s: Int): Float {
        val sec = section(bar)
        if (sec == 0 && bar < 2) return 0f
        if (bar % 8 == 7 && s >= 8) return 0.35f + 0.65f * (s - 8) / 7f
        if (s == 4 || s == 12) return 1f
        if (sec >= 2 && (s == 7 || s == 9 || s == 15 || s == 2) && rnd(bar, s) < 0.55f) return 0.35f
        return 0f
    }

    fun hat(bar: Int, s: Int): Float {
        val sec = section(bar)
        return when {
            sec == 0 -> if (s % 4 == 2) 0.5f else 0f
            sec == 1 -> if (s % 2 == 0) 0.5f else 0f
            else -> if (s % 2 == 0) 0.5f else if (rnd(bar, s + 40) < 0.7f) 0.28f else 0f
        }
    }

    fun bass(bar: Int, s: Int): Int {
        val sec = section(bar)
        val scale = intArrayOf(0, 0, 3, 0, 7, 5, 0, 10)
        val base = scale[hash(bar / 2, 5) and 7]
        return when (sec) {
            0 -> if (s == 0 || (s == 8 && bar >= 2)) 0 else NO
            1 -> if (s == 0 || s == 6 || s == 10) base else NO
            else -> {
                if (s == 0 || s == 3 || s == 6 || s == 10 || s == 12 || s == 14) {
                    base + (if (s == 6 || s == 14) 7 else if (s == 12) 12 else 0)
                } else NO
            }
        }
    }

    fun lead(bar: Int, s: Int): Int {
        val sec = section(bar)
        if (sec < 3) return NO
        val pent = intArrayOf(0, 3, 5, 7, 10, 12, 15, 17)
        val on = if (sec >= 5) rnd(bar, s + 90) > 0.2f else (s % 3 == 0 || s % 4 == 2)
        if (!on) return NO
        return pent[(s * 3 + bar * 2 + (hash(bar, 3) and 3)) and 7]
    }

    fun riser(bar: Int): Boolean = bar % 8 == 7
}

internal class Synth {
    private val stepFrames = LongArray(Tempo.STEPS + 1) { (Tempo.stepStart[it] * MusicController.RATE).toLong() }
    private var frame = 0L
    private var step = -1
    private var seed = 22222
    private var kEnv = 0f
    private var kPitch = 0f
    private var kPh = 0f
    private var sEnv = 0f
    private var sPh = 0f
    private var hEnv = 0f
    private var hPrev = 0f
    private var bFreq = 55f
    private var bEnv = 0f
    private var bP1 = 0f
    private var bP2 = 0f
    private var bLp = 0f
    private var lFreq = 440f
    private var lEnv = 0f
    private var lP = 0f
    private var riserOn = false
    private var riserProg = 0f
    private var rPh = 0f
    private var gain = 1f
    private var drone = 0f
    private var dPh1 = 0f
    private var dPh2 = 0f

    @Volatile
    var mode = 0 // 0 песня, 1 гул, 2 тишина

    private val tau = 6.2831853f

    private fun noise(): Float {
        seed = seed xor (seed shl 13)
        seed = seed xor (seed ushr 17)
        seed = seed xor (seed shl 5)
        return (seed and 0xFFFF) / 32768f - 1f
    }

    private fun trigger(st: Int) {
        val bar = st / 16
        val s = st % 16
        if (Pattern.kick(bar, s)) {
            kEnv = 1f
            kPitch = 170f
        }
        val sn = Pattern.snare(bar, s)
        if (sn > 0f) sEnv = sn
        val h = Pattern.hat(bar, s)
        if (h > 0f) hEnv = h
        val b = Pattern.bass(bar, s)
        if (b != Pattern.NO) {
            bFreq = 55f * 2f.pow(b / 12f)
            bEnv = 1f
        }
        val l = Pattern.lead(bar, s)
        if (l != Pattern.NO) {
            lFreq = 220f * 2f.pow(l / 12f)
            lEnv = 1f
        }
        riserOn = Pattern.riser(bar)
        riserProg = (s + 1) / 16f
    }

    fun render(out: ShortArray) {
        val inv = 1f / MusicController.RATE
        val gTarget = if (mode == 0) 1f else 0f
        val dTarget = if (mode == 1) 0.55f else 0f
        for (i in out.indices) {
            while (step + 1 <= Tempo.STEPS && frame >= stepFrames[step + 1]) {
                step++
                if (step < Tempo.STEPS) trigger(step) else if (mode == 0) mode = 1
            }
            val n = noise()
            var m = 0f
            if (kEnv > 0.001f) {
                kPh += tau * (42f + kPitch) * inv
                if (kPh > tau) kPh -= tau
                m += sin(kPh) * kEnv * 1.1f
                kEnv *= 0.99984f
                kPitch *= 0.9991f
            }
            if (sEnv > 0.001f) {
                sPh += tau * 185f * inv
                if (sPh > tau) sPh -= tau
                m += (n * 0.55f + sin(sPh) * 0.45f) * sEnv * 0.8f
                sEnv *= 0.99975f
            }
            if (hEnv > 0.001f) {
                m += (n - hPrev) * hEnv * 0.3f
                hEnv *= 0.9985f
            }
            hPrev = n
            if (bEnv > 0.001f) {
                bP1 += bFreq * inv
                if (bP1 >= 1f) bP1 -= 1f
                bP2 += bFreq * 1.007f * inv
                if (bP2 >= 1f) bP2 -= 1f
                val saw = (bP1 * 2f - 1f) + (bP2 * 2f - 1f)
                bLp += (0.04f + bEnv * 0.25f) * (saw * 0.5f - bLp)
                m += bLp * bEnv * (1f - 0.85f * kEnv) * 0.9f
                bEnv *= 0.99992f
            }
            if (lEnv > 0.001f) {
                lP += lFreq * inv
                if (lP >= 1f) lP -= 1f
                m += (if (lP < 0.5f) 0.2f else -0.2f) * lEnv * 0.35f
                lEnv *= 0.9996f
            }
            if (riserOn) {
                rPh += tau * (200f + 2200f * riserProg) * inv
                if (rPh > tau) rPh -= tau
                m += n * 0.18f * riserProg * riserProg + sin(rPh) * 0.1f * riserProg
            }
            gain += (gTarget - gain) * 0.00012f
            drone += (dTarget - drone) * 0.00005f
            var d = 0f
            if (drone > 0.002f) {
                dPh1 += tau * 41f * inv
                if (dPh1 > tau) dPh1 -= tau
                dPh2 += tau * 61.5f * inv
                if (dPh2 > tau) dPh2 -= tau
                d = (sin(dPh1) * 0.5f + sin(dPh2) * 0.2f + n * 0.02f) * drone
            }
            val x = m * gain * 1.3f + d
            out[i] = (tanh(x * 1.6f) * 0.85f * 32767f).toInt().toShort()
            frame++
        }
    }
}

class MusicController {
    companion object {
        const val RATE = 44100
        const val LATENCY = 0.06
    }

    private var track: AudioTrack? = null
    private var thread: Thread? = null
    private val synth = Synth()

    @Volatile
    private var running = false

    @Volatile
    private var wantPlay = true

    @Volatile
    private var blocks = 0
    private var fallbackStart = 0L
    private var lastHead = -1
    private var lastHeadNano = 0L
    private var lastOut = 0.0

    fun start() {
        val t = try { build() } catch (e: Exception) { null }
        track = t
        running = true
        if (t != null) {
            thread = Thread({ loop(t) }, "crackcore-audio").also {
                it.priority = Thread.MAX_PRIORITY
                it.start()
            }
        } else {
            fallbackStart = System.nanoTime()
        }
    }

    private fun build(): AudioTrack? {
        val minBuf = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val size = maxOf(minBuf, 4096) * 2
        val tr = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(size)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        if (tr.state != AudioTrack.STATE_INITIALIZED) {
            tr.release()
            return null
        }
        return tr
    }

    private fun loop(t: AudioTrack) {
        val buf = ShortArray(512)
        while (running) {
            synth.render(buf)
            val w = try { t.write(buf, 0, buf.size) } catch (e: Exception) { -1 }
            if (w < 0) break
            blocks++
            if (blocks == 4 && wantPlay) {
                try { t.play() } catch (e: Exception) { }
            }
        }
    }

    /** Время песни в секундах по аудио-часам (позиция воспроизведения), сглаженное. */
    fun time(): Double {
        val t = track ?: return (System.nanoTime() - fallbackStart) / 1e9
        val head = try { t.playbackHeadPosition } catch (e: Exception) { 0 }
        val now = System.nanoTime()
        if (head != lastHead) {
            lastHead = head
            lastHeadNano = now
        }
        val playing = t.playState == AudioTrack.PLAYSTATE_PLAYING
        val extra = if (playing) min((now - lastHeadNano) / 1e9, 0.025) else 0.0
        val v = head.toDouble() / RATE + extra - LATENCY
        if (v > lastOut) lastOut = v
        return lastOut
    }

    fun fadeToDrone() {
        if (synth.mode == 0) synth.mode = 1
    }

    fun silence() {
        synth.mode = 2
    }

    fun pause() {
        wantPlay = false
        try { track?.pause() } catch (e: Exception) { }
    }

    fun resume() {
        wantPlay = true
        if (blocks >= 4) {
            try { track?.play() } catch (e: Exception) { }
        }
    }

    fun stop() {
        running = false
        try { thread?.join(400) } catch (e: Exception) { }
        try { track?.stop() } catch (e: Exception) { }
        try { track?.release() } catch (e: Exception) { }
        track = null
        thread = null
    }
}
