package com.langhua98.vania

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.min

/// 跟着人转：前摄在手机里找人脸（ML Kit，模型打包在 App 里，不联网），人偏离画面中间就让云台慢慢跟过去。
/// 对应 iPhone 版的 Follow.swift，参数和控制一样。本地的反射动作，不经过大模型；大模型只管开关。
/// - 默认是关的，每次打开 App 都要重新开。开着的时候屏幕上方一直显示「摄像头」的提示。画面只在手机里算人脸的位置，不保存、不上传。
/// - 优先级最低：主人、遥控端、大模型让云台转的时候让开，几秒后接着跟。
/// - 手机竖着夹在云台上用。左右、上下哪边是正，第一次用会自己试出来，记在手机上。
class FaceFollower(private val ctx: Context, private val gimbal: Gimbal) {
    enum class Phase(val label: String) {
        OFF("没开"), STARTING("正在打开摄像头"), SEARCHING("在找人"), FOLLOWING("跟着人"),
        NO_CAMERA("摄像头打不开"), DENIED("没有相机权限"), NO_GIMBAL("云台没连上"),
    }
    var phase = Phase.OFF; private set
    val onChange = mutableSetOf<() -> Unit>()
    var onSensing: ((Boolean) -> Unit)? = null
    var onLog: ((String) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val prefs = ctx.getSharedPreferences("follow", Context.MODE_PRIVATE)
    private val detector = FaceDetection.getClient(FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST).setMinFaceSize(0.08f).build())
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private val owner = OneShotLifecycle()
    private var ownerStarted = false

    // 只在分析线程用
    private var lastFrame = 0L
    private var shot: CompletableDeferred<ByteArray?>? = null

    // 只在主线程用
    private var wanted = false
    private var fov = doubleArrayOf(48.0, 62.0)   // 竖屏时画面左右、上下各看多少度
    private var lastSeen = 0L
    private var recentered = true
    private val moving = booleanArrayOf(false, false)
    private val flip = doubleArrayOf(if (prefs.getBoolean("flip.0", false)) -1.0 else 1.0, if (prefs.getBoolean("flip.1", false)) -1.0 else 1.0)
    private val grow = intArrayOf(0, 0)
    private val prevErr = doubleArrayOf(0.0, 0.0)
    private var sensingTimer: Runnable? = null

    val running get() = wanted

    private fun changed() = onChange.toList().forEach { it() }
    private fun setPhase(p: Phase) { if (phase != p) { phase = p; changed() } }

    /// 能不能开。不能的话返回原因
    fun blocker(): String? {
        if (!gimbal.connected) return "云台没连上，跟不了（请主人在设置页点「连接云台」）。"
        if (ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            return "没有相机权限，跟不了（请主人在设置页点「授权硬件」）。"
        return null
    }

    // ───────── 开关（主线程） ─────────

    fun start() {
        if (wanted) return
        wanted = true
        lastSeen = 0; recentered = true
        moving.fill(false); grow.fill(0)
        setPhase(Phase.STARTING)
        onSensing?.invoke(true)
        // 别的指令做完会把屏幕上的提示清掉，所以隔一会儿重新亮一次
        val t = object : Runnable { override fun run() { if (wanted) { onSensing?.invoke(true); main.postDelayed(this, 2000) } } }
        sensingTimer = t
        main.postDelayed(t, 2000)
        fov = cameraFov()
        val f = ProcessCameraProvider.getInstance(ctx)
        f.addListener({
            if (!wanted) return@addListener
            try {
                val p = f.get()
                provider = p
                val a = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                a.setAnalyzer(worker) { img -> analyze(img) }
                analysis = a
                if (!ownerStarted) { owner.start(); ownerStarted = true }
                p.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, a)
                setPhase(Phase.SEARCHING)
                onLog?.invoke("跟随：开了，在用前摄找人脸")
            } catch (e: Exception) {
                fail(Phase.NO_CAMERA)
            }
        }, ContextCompat.getMainExecutor(ctx))
    }

    fun stop() {
        if (!wanted) return
        wanted = false
        sensingTimer?.let { main.removeCallbacks(it) }; sensingTimer = null
        analysis?.let { provider?.unbind(it) }; analysis = null
        if (moving[0] || moving[1]) gimbal.track(0.0, 0.0)
        moving.fill(false)
        setPhase(Phase.OFF)
        onSensing?.invoke(false)
        onLog?.invoke("跟随：关了")
    }

    private fun fail(p: Phase) {
        wanted = false
        sensingTimer?.let { main.removeCallbacks(it) }; sensingTimer = null
        analysis?.let { provider?.unbind(it) }; analysis = null
        setPhase(p)
        onSensing?.invoke(false)
        onLog?.invoke("跟随：${p.label}")
    }

    /// 借一帧画面（JPEG，已经摆正）。跟随开着的时候前摄被这里占着，「看一眼」从这里拿。没在跑、1.5 秒没等到都返回 null
    suspend fun snapshot(): ByteArray? {
        if (!wanted || (phase != Phase.SEARCHING && phase != Phase.FOLLOWING)) return null
        val d = CompletableDeferred<ByteArray?>()
        worker.execute { shot = d }
        return withTimeoutOrNull(1500) { d.await() }
    }

    /// 前摄的视角（度）：竖屏时左右、上下
    private fun cameraFov(): DoubleArray {
        try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            for (id in cm.cameraIdList) {
                val c = cm.getCameraCharacteristics(id)
                if (c.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_FRONT) continue
                val size = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: continue
                val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: continue
                fun deg(mm: Float) = Math.toDegrees(2 * atan(mm / (2.0 * focal)))
                // 传感器的长边是横着的；竖屏时左右看到的是短边
                val long = deg(max(size.width, size.height)); val short = deg(min(size.width, size.height))
                if (long > 20) return doubleArrayOf(short, long)
            }
        } catch (e: Exception) {}
        return doubleArrayOf(48.0, 62.0)
    }

    // ───────── 每一帧（分析线程） ─────────

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun analyze(img: ImageProxy) {
        try {
            val rot = img.imageInfo.rotationDegrees
            shot?.let { d ->
                shot = null
                d.complete(try { jpeg(img, rot) } catch (e: Exception) { null })
            }
            val now = System.currentTimeMillis()
            if (now - lastFrame < EVERY_MS) return
            lastFrame = now
            val media = img.image ?: return
            val faces = try { Tasks.await(detector.process(InputImage.fromMediaImage(media, rot))) } catch (e: Exception) { return }
            // 摆正以后的画面大小（转了 90° 宽高对调）
            val w = (if (rot % 180 == 0) img.width else img.height).toDouble()
            val h = (if (rot % 180 == 0) img.height else img.width).toDouble()
            // 最大的那张脸（离得最近的人），换成 0～1 的比例：x 从左往右，y 从下往上（和 iPhone 版一样）
            val best = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }?.boundingBox
            val center = best?.let { doubleArrayOf(it.exactCenterX() / w, 1 - it.exactCenterY() / h) }
            main.post { step(center) }
        } finally {
            img.close()
        }
    }

    private fun jpeg(img: ImageProxy, rot: Int): ByteArray {
        var b = img.toBitmap()
        if (rot != 0) b = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
    }

    // ───────── 控制（主线程） ─────────

    private fun step(face: DoubleArray?) {
        if (!wanted) return
        val now = System.currentTimeMillis()
        if (!gimbal.connected) { setPhase(Phase.NO_GIMBAL); moving.fill(false); return }
        if (face == null) {
            if (moving[0] || moving[1]) gimbal.track(0.0, 0.0)
            moving.fill(false); grow.fill(0)
            setPhase(Phase.SEARCHING)
            // 很久看不到人：回正面等着
            if (!recentered && now - lastSeen > RECENTER_AFTER_MS) {
                recentered = true
                MainScope().launch { gimbal.aim(0.0, 0.0, relative = false, who = Gimbal.Owner.FOLLOW) }
            }
            return
        }
        lastSeen = now; recentered = false
        setPhase(Phase.FOLLOWING)
        // 人脸中心离想放的位置差多少度
        val err = doubleArrayOf((face[0] - 0.5) * fov[0], (face[1] - AIM_Y) * fov[1])
        val cmd = doubleArrayOf(0.0, 0.0)
        for (i in 0..1) {
            val e = err[i]
            if (moving[i]) { if (abs(e) < DEAD[i] * 0.5) moving[i] = false }
            else if (abs(e) > DEAD[i]) { moving[i] = true; grow[i] = 0 }
            if (moving[i]) {
                val speed = min(MAX_SPEED, max(MIN_SPEED, abs(e) * GAIN))
                cmd[i] = (if (e > 0) 1 else -1) * speed * flip[i]
                // 方向对不对：连着几帧越转离得越远，就是反了
                if (abs(e) > abs(prevErr[i]) + 1.0) grow[i]++ else if (abs(e) < abs(prevErr[i]) - 0.5) grow[i] = 0
            }
            prevErr[i] = e
        }
        val applied = gimbal.track(cmd[0], cmd[1])
        if (applied == null) {   // 别人正在让云台转（或者刚转完）：让开，等会儿再跟
            moving.fill(false); grow.fill(0)
            return
        }
        for (i in 0..1) {
            val did = if (i == 0) applied.first else applied.second
            if (cmd[i] != 0.0 && did == 0.0) { grow[i] = 0; continue }   // 到限位了，不是方向反了
            if (grow[i] >= 4) {
                flip[i] = -flip[i]; grow[i] = 0
                prefs.edit().putBoolean("flip.$i", flip[i] < 0).apply()
                onLog?.invoke("跟随：${if (i == 0) "左右" else "上下"}方向反了，已改过来")
            }
        }
    }

    companion object {
        // 手感参数，和 iPhone 版一样，按真机反馈调
        private const val EVERY_MS = 180L
        /// 人脸离中间多少度以内不动（左右、上下）；回到一半以内才停，免得在边上来回抖
        private val DEAD = doubleArrayOf(5.0, 6.0)
        /// 偏 1 度转多快（度每秒）、最慢、最快
        private const val GAIN = 1.2
        private const val MIN_SPEED = 5.0
        private const val MAX_SPEED = 40.0
        /// 人脸中心想放在画面的哪里（从下往上 0～1）：比正中高一点
        private const val AIM_Y = 0.56
        /// 多久看不到人就回正
        private const val RECENTER_AFTER_MS = 10_000L
    }
}
