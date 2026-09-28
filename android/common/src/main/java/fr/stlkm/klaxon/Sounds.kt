package fr.stlkm.klaxon

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

const val MAX_HOLD = 4000L  // ms : un klaxon long s'arrête tout seul au bout de ce temps

/** nom -> libellé, emoji, durée minimale d'un simple clic (ms) */
class Sound(val key: String, val label: String, val emoji: String, val minMs: Long)

val SOUNDS = listOf(
    Sound("klaxon", "Klaxon", "🚗", 600),
    Sound("pouet", "Pouet", "🤡", 350),
    Sound("camion", "Camion", "🚛", 800),
    Sound("vuvuzela", "Vuvuzela", "🎺", 700),
    Sound("canard", "Canard", "🦆", 200),
)

fun soundOf(k: Any?): Sound = SOUNDS.firstOrNull { it.key == k } ?: SOUNDS[0]

/** Une boucle d'exactement 1 s (fréquences entières) : un klaxon long boucle sans clic.
 *  Mêmes formules que dans index.html et windows/klaxon.py. */
fun synth(kind: String, rate: Int): ShortArray {
    val tau2 = 2 * PI
    val out = DoubleArray(rate)
    var phase = 0.0
    for (i in 0 until rate) {
        val t = i.toDouble() / rate
        var s = 0.0
        when (kind) {
            "klaxon" -> for (f in intArrayOf(415, 523)) s += tanh(4 * sin(tau2 * f * t)) + 0.3 * sin(tau2 * 2 * f * t)
            "camion" -> {
                for (f in intArrayOf(185, 233, 277)) s += tanh(2.5 * sin(tau2 * f * t)) + 0.5 * sin(tau2 * 2 * f * t)
                s += 0.6 * sin(tau2 * 92 * t)
            }
            "vuvuzela" -> {
                val ph = tau2 * 235 * t + 0.6 * sin(tau2 * 5 * t)
                for (k in 1..10) s += sin(k * ph) / k.toDouble().pow(0.8)
            }
            "pouet" -> {
                val tau = t % 0.5
                val len = 0.32
                if (tau < 1.0 / rate) phase = 0.0
                if (tau < len) {
                    phase += tau2 * (360 + 120 * sin(PI * tau / len)) / rate
                    s = (tanh(3 * sin(phase)) + 0.4 * sin(2 * phase)) * sin(PI * tau / len).pow(0.6)
                }
            }
            "canard" -> {
                val tau = t % (1.0 / 3)
                val len = 0.17
                if (tau < 1.0 / rate) phase = 0.0
                if (tau < len) {
                    phase += tau2 * (210 - 300 * tau) / rate
                    s = tanh(8 * sin(phase)) * (0.6 + 0.4 * sin(tau2 * 1100 * t)) * min(1.0, tau / 0.01) * sqrt(1 - tau / len)
                }
            }
        }
        out[i] = s
    }
    val peak = out.maxOf { abs(it) }.takeIf { it > 0 } ?: 1.0
    return ShortArray(rate) { (out[it] / peak * 0.9 * 32000).toInt().toShort() }
}

/** Plusieurs klaxons peuvent sonner en même temps : une piste audio par klaxon, qui boucle.
 *  [cache] garde les sons calculés d'un lancement à l'autre (le calcul est lent sur une montre). */
class Player(private val rate: Int = 44100, private val cache: File? = null) {
    private val main = Handler(Looper.getMainLooper())
    private val pcm = HashMap<String, ShortArray>()
    private val voices = HashMap<String, Voice>()
    @Volatile var loud = false   // canal alarme : ignore le mode silencieux
    @Volatile var usage: Int? = null  // impose un canal (la montre coupe tous les sons de notification)

    private class Voice(val track: AudioTrack, val t0: Long, val minMs: Long)

    init {
        Thread { for (s in SOUNDS) pcm(s.key) }.start()  // prépare les sons sans bloquer le lancement
    }

    private fun pcm(k: String): ShortArray = synchronized(pcm) {
        pcm.getOrPut(k) {
            val file = cache?.let { File(it, "klaxon_${k}_$rate.pcm") }
            file?.takeIf { it.length() == rate * 2L }?.let { f ->
                val bytes = f.readBytes()
                return@getOrPut ShortArray(rate) { ((bytes[2 * it + 1].toInt() shl 8) or (bytes[2 * it].toInt() and 0xFF)).toShort() }
            }
            synth(k, rate).also { data ->
                try {
                    file?.writeBytes(ByteArray(rate * 2) { i -> (data[i / 2].toInt() shr (if (i % 2 == 0) 0 else 8)).toByte() })
                } catch (_: Exception) {
                }
            }
        }
    }

    /** Au fil principal (téléphone). */
    fun start(key: String, sound: String) = main.post { startNow(key, sound) }
    fun end(key: String) = main.post {
        val wait = waitBeforeEnd(key) ?: return@post
        main.postDelayed({ stop(key) }, wait)
    }

    /** Tout de suite, dans le fil appelant : pour la montre, qui gèle l'appli dès que le message est traité. */
    fun startNow(key: String, sound: String) {
        synchronized(voices) { if (key in voices) return }
        val data = pcm(soundOf(sound).key)
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(usage ?: if (loud) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(data.size * 2)
                .build()
        } catch (e: Exception) {
            return
        }
        track.write(data, 0, data.size)
        // un nombre fini de boucles : le son s'arrête au bout de MAX_HOLD même si personne ne l'arrête
        track.setLoopPoints(0, data.size, (MAX_HOLD / 1000 - 1).toInt())
        track.play()
        synchronized(voices) { voices[key] = Voice(track, SystemClock.uptimeMillis(), soundOf(sound).minMs) }
        main.postDelayed({ stop(key) }, MAX_HOLD)
    }

    /** Fin d'un klaxon : un simple clic joue quand même le son en entier (attend dans le fil appelant). */
    fun endNow(key: String) {
        val wait = waitBeforeEnd(key) ?: return
        if (wait > 0) Thread.sleep(wait)
        stop(key)
    }

    private fun waitBeforeEnd(key: String): Long? {
        val v = synchronized(voices) { voices[key] } ?: return null
        return maxOf(0, v.t0 + v.minMs - SystemClock.uptimeMillis())
    }

    fun beep(sound: String) {
        val key = "essai/" + SystemClock.uptimeMillis()
        start(key, sound)
        end(key)
    }

    fun beepNow(sound: String) {
        val key = "essai/" + SystemClock.uptimeMillis()
        startNow(key, sound)
        endNow(key)
    }

    private fun stop(key: String) {
        val v = synchronized(voices) { voices.remove(key) } ?: return
        try {
            v.track.stop()
        } catch (_: Exception) {
        }
        v.track.release()
    }
}
