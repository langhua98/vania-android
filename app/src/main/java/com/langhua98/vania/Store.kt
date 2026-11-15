package com.langhua98.vania

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/// 设置。普通设置存 SharedPreferences；密钥（大模型、Gemini、眼睛、云盘令牌、配对口令）用安卓的密钥库加密后再存，
/// 不写进日志。对应 iOS 版的 Store.swift（那边是钥匙串）。
/// 改了就存，不用点保存。界面想跟着变就注册 onChange。
class Store(ctx: Context) {
    private val p: SharedPreferences = ctx.getSharedPreferences("vania", Context.MODE_PRIVATE)
    private val listeners = mutableListOf<(String) -> Unit>()

    /// 某个设置变了（参数是名字）。在主线程调
    fun onChange(l: (String) -> Unit) { listeners += l }
    private fun changed(key: String) { listeners.forEach { it(key) } }

    private fun str(key: String, def: String) = p.getString(key, null)?.takeIf { it.isNotEmpty() } ?: def
    private fun setStr(key: String, v: String) { p.edit().putString(key, v).apply(); changed(key) }
    private fun bool(key: String, def: Boolean) = p.getBoolean(key, def)
    private fun setBool(key: String, v: Boolean) { p.edit().putBoolean(key, v).apply(); changed(key) }
    private fun secret(key: String) = Secret.get(p, key)
    private fun setSecret(key: String, v: String) { Secret.set(p, key, v); changed(key) }

    // 云盘和待命（第 2 批用）
    var base: String get() = str("base", "https://node.tail823dbd.ts.net"); set(v) = setStr("base", v)
    var device: String get() = str("device", Build.MODEL ?: "Android"); set(v) = setStr("device", v)
    var pair: String get() = secret("pair"); set(v) = setSecret("pair", v)
    var token: String get() = secret("token"); set(v) = setSecret("token", v)
    var autoStandby: Boolean get() = bool("autoStandby", false); set(v) = setBool("autoStandby", v)

    // 对话的大模型（OpenAI 兼容接口，默认 DeepSeek）
    var llmBase: String get() = str("llmBase", DEFAULT_LLM_BASE); set(v) = setStr("llmBase", v)
    var llmModel: String
        get() = str("llmModel", LLM_MODELS[0].first).takeIf { m -> LLM_MODELS.any { it.first == m } } ?: LLM_MODELS[0].first
        set(v) = setStr("llmModel", v)
    var llmKey: String get() = secret("llmKey"); set(v) = setSecret("llmKey", v)
    /// Gemini 密钥：填了就用实时语音（第 5 批）
    var geminiKey: String get() = secret("geminiKey"); set(v) = setSecret("geminiKey", v)

    // 云台、眼睛（第 3、4 批）
    var gimbalAuto: Boolean get() = bool("gimbalAuto", false); set(v) = setBool("gimbalAuto", v)
    var allowSee: Boolean get() = bool("allowSee", false); set(v) = setBool("allowSee", v)
    var eyeBase: String get() = str("eyeBase", ""); set(v) = setStr("eyeBase", v)
    var eyeModel: String get() = str("eyeModel", ""); set(v) = setStr("eyeModel", v)
    var eyeKey: String get() = secret("eyeKey"); set(v) = setSecret("eyeKey", v)
    /// 跟着人转：不存，每次打开 App 都是关的
    var follow = false

    // 机器人
    var outfit: String
        get() = str("outfit", "default").takeIf { o -> OUTFITS.any { it.id == o } } ?: "default"
        set(v) = setStr("outfit", v)
    var background: String
        get() = str("background", "auto").takeIf { b -> BACKGROUNDS.any { it.id == b } } ?: "auto"
        set(v) = setStr("background", v)
    /// high = 全部效果，low = 省电（不描边、不算物理、贴图缩到 1024）
    var faceQuality: String get() = if (str("faceQuality", "high") == "low") "low" else "high"; set(v) = setStr("faceQuality", v)

    // 处理过的遥控指令 id（避免重复执行，第 2 批用）
    fun markSeen(id: String) {
        val s = (p.getString("seen", "") ?: "").split('\n').filter { it.isNotEmpty() }.toMutableList()
        if (id in s) return
        s += id
        p.edit().putString("seen", s.takeLast(500).joinToString("\n")).apply()
    }
    fun isSeen(id: String) = (p.getString("seen", "") ?: "").split('\n').contains(id)

    data class Item(val id: String, val name: String, val words: List<String>)

    companion object {
        const val DEFAULT_LLM_BASE = "https://api.deepseek.com/v1"
        val LLM_MODELS = listOf("deepseek-chat" to "对话（快）", "deepseek-reasoner" to "深度思考（慢）")

        /// 衣服：id、名字、对她说话时认的叫法。和 assets/live2d 里网页的 OUTFITS 一致
        val OUTFITS = listOf(
            Item("default", "校服", listOf("校服", "原版", "原来", "第一套", "一号")),
            Item("outfit2", "洋装", listOf("洋装", "婚纱", "第二套", "二号")),
            Item("sporty", "运动装", listOf("运动装", "运动", "外套", "夹克", "第三套", "三号")),
        )
        /// 背景：id、名字、叫法。和网页的 SCENES 一致
        val BACKGROUNDS = listOf(
            Item("auto", "卧室（跟着时间变）", listOf("自动", "跟着时间")),
            Item("room_dawn", "卧室·清晨", listOf("清晨", "早上", "黄昏", "傍晚")),
            Item("room_day", "卧室·白天", listOf("白天", "下午", "中午")),
            Item("room_night", "卧室·夜晚", listOf("夜晚", "晚上", "夜里")),
            Item("room_lamp", "卧室·暖灯", listOf("暖灯", "小灯", "书架")),
            Item("gothic", "哥特大厅", listOf("哥特", "大厅", "城堡", "万圣节")),
            Item("city", "城市夜景", listOf("城市", "夜景", "原来的背景")),
        )

        fun outfitName(id: String) = OUTFITS.firstOrNull { it.id == id }?.name ?: "原版"
        fun backgroundName(id: String) = BACKGROUNDS.firstOrNull { it.id == id }?.name ?: "卧室"

        /// 画质 → 网页的网址参数
        fun faceQuery(quality: String) = if (quality == "low") "&outline=0&spring=0&tex=1024" else ""

        /// 语音识别有时给繁体：先转简体（Android 10 起系统自带转换），再去掉空格
        fun simplified(s: String): String {
            val t = if (Build.VERSION.SDK_INT >= 29) {
                try { android.icu.text.Transliterator.getInstance("Hant-Hans").transliterate(s) } catch (e: Throwable) { s }
            } else s
            return t.replace(" ", "")
        }

        /// 名字、叫法或 id → 衣服 id
        fun outfitID(named: String): String? {
            val text = simplified(named)
            OUTFITS.firstOrNull { it.id == text.trim() }?.let { return it.id }
            return OUTFITS.firstOrNull { o -> o.words.any { text.contains(it) } }?.id
        }

        /// 「换上洋装」「换回原来的」「换件衣服」→ 衣服 id；不是换装返回 null
        fun outfitCommand(raw: String, current: String = "default"): String? {
            val text = simplified(raw)
            if (listOf("换", "穿上", "穿回").none { text.contains(it) }) return null
            if (text.contains("换回") || text.contains("穿回")) return "default"
            outfitID(text)?.let { return it }
            if (listOf("换件衣服", "换个衣服", "换衣服", "换一套", "换套衣服", "换身衣服", "换装", "换一身").any { text.contains(it) }) {
                val i = OUTFITS.indexOfFirst { it.id == current }.coerceAtLeast(0)
                return OUTFITS[(i + 1) % OUTFITS.size].id
            }
            return null
        }

        /// 「背景 哥特大厅」「换个背景」「换到卧室夜晚」→ 背景 id；不是换背景返回 null
        fun backgroundCommand(raw: String, current: String = "auto"): String? {
            val text = simplified(raw)
            if (!(text.contains("背景") || text.startsWith("换到") || text.startsWith("去"))) return null
            BACKGROUNDS.firstOrNull { text.contains(it.name) || text.contains(it.id) }?.let { return it.id }
            // 叫法从长的开始比，避免「卧室」先把「卧室夜晚」截走
            val words = BACKGROUNDS.flatMap { b -> b.words.map { b.id to it } }.sortedByDescending { it.second.length }
            words.firstOrNull { text.contains(it.second) }?.let { return it.first }
            if (text.contains("卧室")) return "auto"
            if (text.contains("背景")) {
                val i = BACKGROUNDS.indexOfFirst { it.id == current }.coerceAtLeast(0)
                return BACKGROUNDS[(i + 1) % BACKGROUNDS.size].id
            }
            return null
        }
    }
}

/// 用安卓密钥库里的一把 AES 钥匙加密密钥类的设置。钥匙只在这台手机里，拿不出来
private object Secret {
    private const val ALIAS = "vania-secrets"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build())
        return g.generateKey()
    }

    fun set(p: SharedPreferences, name: String, value: String) {
        if (value.isEmpty()) { p.edit().remove("secret.$name").apply(); return }
        try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key())
            val out = c.iv + c.doFinal(value.toByteArray(Charsets.UTF_8))
            p.edit().putString("secret.$name", Base64.encodeToString(out, Base64.NO_WRAP)).apply()
        } catch (e: Exception) {
            p.edit().remove("secret.$name").apply()
        }
    }

    fun get(p: SharedPreferences, name: String): String {
        val raw = p.getString("secret.$name", null) ?: return ""
        return try {
            val all = Base64.decode(raw, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all, 0, 12))
            String(c.doFinal(all, 12, all.size - 12), Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }
}
