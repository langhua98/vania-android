package com.langhua98.vania

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.roundToInt

/// 她的眼睛：拍一张照片，变成一段文字交给聊天的大模型（聊天的模型自己不用会看图）。对应 iPhone 版的 Eyes.swift。两层：
/// 1. 手机自己识别（ML Kit，模型打包在 App 里，不联网、不要密钥）：有几个人、画面大概是什么东西、看到什么字。比较粗。
/// 2. 设置里填了「眼睛」模型（能看图的大模型，OpenAI 兼容接口）就把照片发给它描述，细得多；发不通时退回第 1 层。
/// 规则：设置里「允许她用摄像头看」默认关；只在 App 在前台时拍；拍的时候屏幕上方亮摄像头的提示；
/// 照片不存到手机上，只在填了「眼睛」模型时发给主人自己配的那个接口；日志里只记拍了多大，不记内容。
object Eyes {
    private const val MAX_SIDE = 768
    private const val QUALITY = 60
    private const val MIN_CONFIDENCE = 0.5f
    private const val MAX_LABELS = 8
    private const val MAX_LINES = 8

    /// 跟随开着时前摄被它占着，从它那里借一帧（VaniaApp 填上）
    var borrowFrame: (suspend () -> ByteArray?)? = null

    /// 看一眼。返回（给大模型看的一段话，给记录用的一句话）
    suspend fun look(front: Boolean, store: Store, face: FaceBridge): Pair<String, String> {
        val app = VaniaApp.instance
        if (!store.allowSee) return "主人没有打开「允许她用摄像头看」，你现在看不见。可以请主人到设置里打开。" to "没开「允许她用摄像头看」"
        if (!app.foreground) return "App 不在前台，摄像头用不了。" to "App 不在前台"
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            return "没有相机权限，看不了。请主人在设置里点「授权硬件」。" to "没有相机权限"
        face.send("type" to "sensing", "camera" to true)
        try {
            var usedFront = front
            val raw = borrowFrame?.invoke()?.also { usedFront = true }   // 跟随开着：只能用它正在用的前摄
                ?: try { Photo.take(app, front) } catch (e: Exception) { null }
            val jpeg = raw?.let { shrink(it) } ?: return "摄像头没拍到画面，看不了。" to "没拍到"
            val which = if (usedFront) "前面的摄像头（屏幕朝着的方向）" else "后面的摄像头（手机背面朝着的方向）"
            val tag = "${if (usedFront) "前" else "后"}摄，${jpeg.size / 1024} KB"
            if (store.eyeBase.isNotEmpty() && store.eyeModel.isNotEmpty() && store.eyeKey.isNotEmpty()) {
                return try {
                    val said = describe(jpeg, store.eyeBase, store.eyeModel, store.eyeKey)
                    "你用${which}看了一眼，看到的是：$said" to "看了一眼（$tag，眼睛模型）"
                } catch (e: Exception) {
                    "你用${which}看了一眼。眼睛模型没连上，下面是手机自己认出来的，比较粗：${recognize(app, jpeg)}" to
                        "看了一眼（$tag，眼睛模型出错：${e.message}，改用手机自己识别）"
                }
            }
            return "你用${which}看了一眼。下面是手机自己认出来的，比较粗，只有大类，没有细节：${recognize(app, jpeg)}" to "看了一眼（$tag，手机自己识别）"
        } finally {
            face.send("type" to "sensing", "camera" to false)
        }
    }

    /// 缩小（长边 768）并压成 JPEG（照片的方向按 EXIF 摆正）
    private fun shrink(data: ByteArray): ByteArray? {
        var b = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return null
        val deg = try {
            when (androidx.exifinterface.media.ExifInterface(data.inputStream()).getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, 1)) {
                6 -> 90; 3 -> 180; 8 -> 270; else -> 0
            }
        } catch (e: Exception) { 0 }
        if (deg != 0) b = Bitmap.createBitmap(b, 0, 0, b.width, b.height, android.graphics.Matrix().apply { postRotate(deg.toFloat()) }, true)
        val k = minOf(1.0, MAX_SIDE.toDouble() / max(b.width, b.height))
        if (k < 1) b = Bitmap.createScaledBitmap(b, (b.width * k).roundToInt(), (b.height * k).roundToInt(), true)
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }.toByteArray()
    }

    // ───────── 第 1 层：手机自己识别 ─────────

    /// 认出来的东西写成一句话。东西的名字是 ML Kit 给的英文词（比如 Bookcase、Bed），聊天的大模型看得懂
    suspend fun recognize(ctx: Context, jpeg: ByteArray): String = withContext(Dispatchers.Default) {
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return@withContext "认不出画面（照片坏了）。"
        val img = InputImage.fromBitmap(bmp, 0)
        val parts = mutableListOf<String>()
        val faces = try {
            Tasks.await(FaceDetection.getClient(FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE).build()).process(img), 10, TimeUnit.SECONDS).size
        } catch (e: Exception) { 0 }
        parts += if (faces > 0) "看得到 $faces 张人脸" else "没有看到人脸（可能没有人，或者人背对着）"
        val labels = try {
            Tasks.await(ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS).process(img), 10, TimeUnit.SECONDS)
                .filter { it.confidence >= MIN_CONFIDENCE }.sortedByDescending { it.confidence }.take(MAX_LABELS).map { it.text }
        } catch (e: Exception) { emptyList() }
        if (labels.isNotEmpty()) parts += "画面里可能有（英文是系统给的类别名）：" + labels.joinToString("、")
        val lines = try {
            Tasks.await(TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()).process(img), 10, TimeUnit.SECONDS)
                .textBlocks.flatMap { it.lines }.map { it.text.trim() }.filter { it.length >= 2 }.take(MAX_LINES)
        } catch (e: Exception) { emptyList() }
        if (lines.isNotEmpty()) parts += "看到的字：" + lines.joinToString("；")
        if (labels.isEmpty() && lines.isEmpty() && faces == 0) parts += "认不出画面里是什么（可能太暗或者太近）"
        parts.joinToString("。") + "。"
    }

    // ───────── 第 2 层：「眼睛」模型（OpenAI 兼容接口，能看图的） ─────────

    suspend fun describe(jpeg: ByteArray, base: String, model: String, key: String): String = withContext(Dispatchers.IO) {
        val prompt = "用两三句中文口语说出这张照片里有什么：有没有人、人在做什么、主要的东西和它们的位置（左边、右边、中间）、看得清的文字。只说看得到的，看不清就说看不清，不要猜，不要用列表。"
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", prompt))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP))))
        val body = JSONObject().put("model", model).put("max_tokens", 300).put("temperature", 0.2)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
        val req = try {
            Request.Builder().url(base.trim().trimEnd('/') + "/chat/completions").header("Authorization", "Bearer $key")
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        } catch (e: IllegalArgumentException) { throw Exception("接口地址不对") }
        http.newBuilder().callTimeout(25, TimeUnit.SECONDS).build().newCall(req).execute().use { r ->
            if (r.code != 200) throw Exception("接口返回 ${r.code}")
            val text = try {
                JSONObject(r.body!!.string()).getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content").trim()
            } catch (e: Exception) { "" }
            if (text.isEmpty()) throw Exception("接口没有给出描述")
            text
        }
    }
}
