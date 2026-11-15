package com.langhua98.vania

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Location
import android.location.LocationManager
import android.media.MediaRecorder
import android.media.RingtoneManager
import android.os.BatteryManager
import android.os.Build
import android.os.CancellationSignal
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/// 硬件指令：info、torch、photo、audio、location、vibrate、ring、shell。对应 iPhone 版的 Commands.swift。
/// 规则（AGENTS.md）：拍照、录音、定位只在 App 在前台时做（和 iPhone 版一样），做的时候屏幕上方会提示；不在后台偷偷用。
object Commands {
    suspend fun run(ctx: Context, cmd: JSONObject, foreground: Boolean): CmdResult {
        val action = cmd.optString("do")
        return try {
            when (action) {
                "info" -> CmdResult(info(ctx))
                "torch" -> torch(ctx, cmd.optBoolean("on", true))
                "photo" -> if (!foreground) CmdResult("拍不了：Vania 不在前台（拍照要先把 App 打开）") else photo(ctx, cmd.optString("lens") == "front")
                "audio" -> if (!foreground) CmdResult("录不了：Vania 不在前台（录音要先把 App 打开）") else audio(ctx, clamp(cmd, "seconds", 5, 1, 60))
                "location" -> if (!foreground) CmdResult("定位不了：Vania 不在前台") else location(ctx)
                "vibrate" -> { vibrate(ctx, 500); CmdResult("震动了") }
                "ring" -> { ring(ctx, clamp(cmd, "seconds", 5, 1, 60)); CmdResult("响了") }
                "shell" -> CmdResult("Vania 安卓版不跑 shell 命令。要跑命令用装了法莉娅（小橘遥控安卓被控端）的那台。")
                else -> CmdResult("不认识的指令：$action")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            CmdResult("执行出错：${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun clamp(cmd: JSONObject, key: String, def: Int, lo: Int, hi: Int) = cmd.optInt(key, def).coerceIn(lo, hi)

    private fun granted(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    // ───────── 信息 ─────────

    private fun info(ctx: Context): String {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val st = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val charging = st?.getIntExtra(BatteryManager.EXTRA_STATUS, -1).let { it == BatteryManager.BATTERY_STATUS_CHARGING || it == BatteryManager.BATTERY_STATUS_FULL }
        return """
            型号：${Build.MANUFACTURER} ${Build.MODEL}
            系统：Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）
            电量：${if (level in 0..100) "$level%" else "未知"}${if (charging) "（在充电）" else ""}
            App：Vania 安卓版 ${BuildConfig.VERSION_NAME}（构建 ${BuildConfig.BUILD_NUMBER}）
        """.trimIndent()
    }

    // ───────── 手电筒 ─────────

    private fun torch(ctx: Context, on: Boolean): CmdResult {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            ?: return CmdResult("这台设备没有手电筒")
        cm.setTorchMode(id, on)
        return CmdResult("手电筒已${if (on) "打开" else "关闭"}")
    }

    // ───────── 拍照（CameraX，不用预览） ─────────

    private suspend fun photo(ctx: Context, front: Boolean): CmdResult {
        if (!granted(ctx, Manifest.permission.CAMERA)) return CmdResult("没有相机权限，去 Vania 的设置里点「授权硬件」")
        val data = Photo.take(ctx, front)
        return CmdResult("拍了一张（${if (front) "前" else "后"}摄，${data.size / 1024} KB）", listOf("photo.jpg" to data))
    }

    // ───────── 录音 ─────────

    private suspend fun audio(ctx: Context, seconds: Int): CmdResult {
        if (!granted(ctx, Manifest.permission.RECORD_AUDIO)) return CmdResult("没有麦克风权限，去 Vania 的设置里点「授权硬件」")
        val f = File(ctx.cacheDir, "rec-${System.nanoTime()}.m4a")
        val rec = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioSamplingRate(44100)
            rec.setAudioEncodingBitRate(64000)
            rec.setAudioChannels(1)
            rec.setOutputFile(f.absolutePath)
            rec.prepare()
            rec.start()
            delay(seconds * 1000L)
            rec.stop()
        } finally {
            rec.release()
        }
        val data = f.readBytes()
        f.delete()
        return CmdResult("录了 $seconds 秒（${data.size / 1024} KB）", listOf("audio.m4a" to data))
    }

    // ───────── 定位 ─────────

    @SuppressLint("MissingPermission")
    private suspend fun location(ctx: Context): CmdResult {
        if (!granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION) && !granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)) {
            return CmdResult("暂时拿不到定位（没有定位权限，去 Vania 的设置里点「授权硬件」）")
        }
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER).filter {
            try { lm.isProviderEnabled(it) } catch (e: Exception) { false }
        }
        var loc: Location? = null
        if (Build.VERSION.SDK_INT >= 30) {
            for (p in providers.filter { it != LocationManager.PASSIVE_PROVIDER }) {
                loc = withTimeoutOrNull(20_000) {
                    suspendCancellableCoroutine<Location?> { c ->
                        val cancel = CancellationSignal()
                        c.invokeOnCancellation { cancel.cancel() }
                        lm.getCurrentLocation(p, cancel, ctx.mainExecutor) { c.resume(it) }
                    }
                }
                if (loc != null) break
            }
        }
        if (loc == null) loc = providers.mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
        val l = loc ?: return CmdResult("暂时拿不到定位（定位关着，或信号差）")
        return CmdResult("纬度 ${l.latitude}，经度 ${l.longitude}，精度 ${l.accuracy.toInt()} 米\n" +
            "地图：https://uri.amap.com/marker?position=${l.longitude},${l.latitude}（高德，国内坐标会偏几百米）")
    }

    // ───────── 震动、响铃 ─────────

    private fun vibrate(ctx: Context, ms: Long) {
        val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private suspend fun ring(ctx: Context, seconds: Int) {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val tone = uri?.let { RingtoneManager.getRingtone(ctx, it) }
        try {
            tone?.play()
            val end = System.currentTimeMillis() + seconds * 1000L
            while (System.currentTimeMillis() < end) {
                vibrate(ctx, 600)
                if (tone != null && !tone.isPlaying) tone.play()
                delay(1300)
            }
        } finally {
            tone?.stop()
        }
    }
}

/// 不开预览拍一张 JPEG。CameraX 要一个「生命周期」，这里自己造一个，拍完就关
object Photo {
    suspend fun take(ctx: Context, front: Boolean): ByteArray = withContext(Dispatchers.Main) {
        val provider = suspendCancellableCoroutine<ProcessCameraProvider> { c ->
            val f = ProcessCameraProvider.getInstance(ctx)
            f.addListener({ try { c.resume(f.get()) } catch (e: Exception) { c.resumeWithException(e) } }, ContextCompat.getMainExecutor(ctx))
        }
        val owner = OneShotLifecycle()
        val capture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
        val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        try {
            provider.bindToLifecycle(owner, selector, capture)
            owner.start()
            delay(600)   // 让自动曝光稳定一下
            suspendCancellableCoroutine { c ->
                capture.takePicture(ContextCompat.getMainExecutor(ctx), object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        val buf = image.planes[0].buffer
                        val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                        image.close()
                        c.resume(bytes)
                    }
                    override fun onError(e: ImageCaptureException) { c.resumeWithException(Exception("拍照失败：${e.message}")) }
                })
            }
        } finally {
            provider.unbind(capture)
            owner.stop()
        }
    }
}

/// 给 CameraX 用的最小生命周期：start 时开相机，stop 时关
class OneShotLifecycle : LifecycleOwner {
    private val reg = LifecycleRegistry(this).apply { currentState = Lifecycle.State.CREATED }
    override val lifecycle: Lifecycle get() = reg
    fun start() { reg.currentState = Lifecycle.State.RESUMED }
    fun stop() { reg.currentState = Lifecycle.State.DESTROYED }
}
