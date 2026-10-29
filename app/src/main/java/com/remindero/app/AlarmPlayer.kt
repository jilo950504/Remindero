package com.remindero.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Alarma "cósmica": se sintetiza en código (sin archivos de audio).
 * Barrido de sirena sci-fi + armónicos + modulación tipo alien, en bucle.
 */
class AlarmPlayer(private val ctx: Context) {

    private var track: AudioTrack? = null
    private var prevVolume = -1
    private val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Suppress("DEPRECATION")
    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

    fun start(vibrate: Boolean) {
        if (track != null) return

        // Volumen de alarma al máximo (se restaura al silenciar)
        try {
            prevVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(
                AudioManager.STREAM_ALARM,
                audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                0
            )
        } catch (_: Exception) { }

        val sampleRate = 44100
        val buffer = buildCosmicBuffer(sampleRate)

        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(buffer.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        t.write(buffer, 0, buffer.size)
        t.setLoopPoints(0, buffer.size, -1)
        t.play()
        track = t

        if (vibrate) {
            val pattern = longArrayOf(0, 600, 250, 600, 250, 1200, 500)
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
        }
    }

    fun stop() {
        try {
            track?.stop()
            track?.release()
        } catch (_: Exception) { }
        track = null
        vibrator?.cancel()
        if (prevVolume >= 0) {
            try {
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, prevVolume, 0)
            } catch (_: Exception) { }
            prevVolume = -1
        }
    }

    private fun buildCosmicBuffer(sr: Int): ShortArray {
        val seconds = 3.2
        val n = (sr * seconds).toInt()
        val out = ShortArray(n)
        var p1 = 0.0
        var p2 = 0.0
        var p3 = 0.0
        val fade = (sr * 0.005).toInt()

        for (i in 0 until n) {
            val t = i.toDouble() / sr
            // Barrido 380 Hz <-> 1680 Hz, dos subidas/bajadas por ciclo
            val sweep = 0.5 - 0.5 * cos(2 * PI * t / 1.6)
            val f = 380.0 + 1300.0 * sweep
            val wobble = 1.0 + 0.03 * sin(2 * PI * 5.0 * t)

            p1 += 2 * PI * f * wobble / sr
            p2 += 2 * PI * f * 1.5 / sr
            p3 += 2 * PI * (f * 0.5) / sr

            var s = 0.55 * sin(p1) + 0.25 * sin(p2) + 0.30 * sin(p3)
            // Modulación en anillo para el toque "alienígena"
            s *= 0.72 + 0.28 * sin(2 * PI * 30.0 * t)

            // Fundido muy corto en los extremos para evitar "clicks" al repetir
            var env = 1.0
            if (i < fade) env = i.toDouble() / fade
            if (i > n - fade) env = (n - i).toDouble() / fade

            val v = (s * env * 0.85).coerceIn(-1.0, 1.0) * Short.MAX_VALUE
            out[i] = v.toInt().toShort()
        }
        return out
    }
}
