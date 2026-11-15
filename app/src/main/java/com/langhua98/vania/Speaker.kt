package com.langhua98.vania

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlin.math.log10
import kotlin.math.sqrt

/// 放一段语音合成服务给的 WAV，嘴跟着真实的音量动（先把整段的音量算好，播放时按播到哪儿取）。
/// 对应 iOS 版 Talk.play（那边用 AVAudioPlayer 的音量表）。
object WavPlayer {
    class Wav(val pcm: ByteArray, val rate: Int, val channels: Int)

    /// 只认 16 位 PCM 的 WAV（服务给的就是这种）；认不出返回 null
    fun parse(data: ByteArray): Wav? {
        if (data.size < 44 || String(data, 0, 4) != "RIFF" || String(data, 8, 4) != "WAVE") return null
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var rate = 0; var ch = 0; var bits = 0; var fmt = 0
        while (pos + 8 <= data.size) {
            val id = String(data, pos, 4)
            var size = b.getInt(pos + 4)
            val start = pos + 8
            if (id == "fmt ") {
                fmt = b.getShort(start).toInt(); ch = b.getShort(start + 2).toInt(); rate = b.getInt(start + 4); bits = b.getShort(start + 14).toInt()
            } else if (id == "data") {
                if (size < 0 || start + size > data.size) size = data.size - start   // 流式写的 WAV 长度可能是 0 或者很大
                if (fmt != 1 || bits != 16 || rate <= 0 || ch !in 1..2) return null
                return Wav(data.copyOfRange(start, start + size), rate, ch)
            }
            pos = start + size + (size and 1)
        }
        return null
    }

    /// 每 60 毫秒一段的音量（0～1，按分贝换算，和 iOS 版一样：-45 dB 以下是 0，-10 dB 是 1）
    fun envelope(w: Wav, stepMs: Int = 60): FloatArray {
        val frameBytes = 2 * w.channels
        val perStep = (w.rate * stepMs / 1000).coerceAtLeast(1)
        val frames = w.pcm.size / frameBytes
        val out = FloatArray((frames + perStep - 1) / perStep)
        val b = ByteBuffer.wrap(w.pcm).order(ByteOrder.LITTLE_ENDIAN)
        for (i in out.indices) {
            var sum = 0.0; var n = 0
            var f = i * perStep
            val end = minOf(frames, f + perStep)
            while (f < end) { val s = b.getShort(f * frameBytes) / 32768.0; sum += s * s; n++; f++ }
            val rms = if (n > 0) sqrt(sum / n) else 0.0
            val db = if (rms > 1e-9) 20 * log10(rms) else -160.0
            out[i] = ((db + 45) / 35).coerceIn(0.0, 1.0).toFloat()
        }
        return out
    }

    /// 播完返回 true；放不了返回 false。mouth：每 60 毫秒报一次嘴张多大。协程取消时马上停
    suspend fun play(data: ByteArray, mouth: (Float) -> Unit): Boolean {
        val w = parse(data) ?: return false
        val env = envelope(w)
        val chMask = if (w.channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(w.rate).setChannelMask(chMask).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(w.pcm.size.coerceAtLeast(4096))
                .build()
        } catch (e: Exception) { return false }
        try {
            if (track.write(w.pcm, 0, w.pcm.size) < 0) return false
            track.play()
            val total = w.pcm.size / (2 * w.channels)
            val perStep = (w.rate * 60 / 1000).coerceAtLeast(1)
            var still = 0; var last = -1
            while (coroutineContext.isActive) {
                val head = track.playbackHeadPosition
                if (head >= total) break
                // 播放头卡住不动超过 1 秒就算完了（有的机器最后几帧不报）
                if (head == last) { if (++still > 16) break } else { still = 0; last = head }
                mouth(env.getOrElse(head / perStep) { 0f })
                delay(60)
            }
            return coroutineContext.isActive
        } finally {
            mouth(0f)
            try { track.stop() } catch (e: Exception) {}
            track.release()
        }
    }
}

/// 系统自带的朗读：语音合成服务连不上时用它读这一句（嘴由脸自己按节奏动）
class SystemVoice(ctx: Context) {
    private var ready = false
    private var waiting: CompletableDeferred<Unit>? = null
    private val tts: TextToSpeech = TextToSpeech(ctx.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { waiting?.complete(Unit) }
            @Deprecated("old API")
            override fun onError(id: String?) { waiting?.complete(Unit) }
            override fun onError(id: String?, code: Int) { waiting?.complete(Unit) }
            override fun onStop(id: String?, interrupted: Boolean) { waiting?.complete(Unit) }
        })
    }

    suspend fun speak(text: String) {
        if (!ready) return
        tts.language = Locale.SIMPLIFIED_CHINESE
        tts.setSpeechRate(1.05f)
        tts.setPitch(1.15f)
        val done = CompletableDeferred<Unit>()
        waiting = done
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), "vania-" + System.nanoTime()) != TextToSpeech.SUCCESS) return
        try { done.await() } finally { if (!done.isCompleted) tts.stop() }
    }

    fun stop() {
        tts.stop()
        waiting?.complete(Unit)
    }
}
