package com.langhua98.vania

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView

/// 设置页：对话、机器人的画面、记录。对应 iOS 版的 SettingsView（云盘、待命、云台、眼睛在后面几批加）。
/// 改了就存，不用点保存。
class SettingsActivity : Activity() {
    private val app get() = application as VaniaApp
    private lateinit var col: LinearLayout
    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "设置"
        val scroll = ScrollView(this)
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(32)) }
        scroll.addView(col)
        setContentView(scroll)
        val s = app.store

        section("对话（大模型）")
        field("接口地址（默认 DeepSeek）", s.llmBase, InputType.TYPE_TEXT_VARIATION_URI) { s.llmBase = it }
        choice(Store.LLM_MODELS, s.llmModel) { s.llmModel = it }
        field("API 密钥", s.llmKey, InputType.TYPE_TEXT_VARIATION_PASSWORD, secret = true) { s.llmKey = it }
        note("默认用 DeepSeek（${Store.DEFAULT_LLM_BASE}），填 DeepSeek 的 API 密钥就能用。模型二选一：deepseek-chat 回答快；deepseek-reasoner 先想再答，慢一些。填完自动保存。密钥加密存在这台手机上。")

        section("机器人")
        label("衣服")
        spinner(Store.OUTFITS.map { it.id to it.name }, s.outfit) { s.outfit = it }
        label("背景")
        spinner(Store.BACKGROUNDS.map { it.id to it.name }, s.background) { s.background = it }
        label("画质")
        spinner(listOf("high" to "高", "low" to "省电"), s.faceQuality) { s.faceQuality = it }
        note("打开 App 就是 3D 角色（Yuki）。点麦克风开始聊天，之后像打电话一样轮流说，说「再见」结束；也可以打字。可以对她说「换上洋装」「跳个舞」「转过去」「让我看看你的脸」。闲着的时候点她的头或手，她会有反应。画面卡就把画质改成「省电」。")

        section("这一版还没有的")
        note("安卓版是照着 iPhone 版 Vania 一批一批做的。这一版有：3D 角色、聊天框、快捷菜单、大模型对话、她说话、换装、背景、镜头、动作。还没有：云盘待命和遥控、大疆云台、跟着人转、眼睛、Gemini 实时语音，后面几版加上。")

        section("记录")
        logView = TextView(this).apply { textSize = 11f; typeface = Typeface.MONOSPACE; setTextColor(0xFFCCCCCC.toInt()) }
        col.addView(logView)
        refreshLog()
        app.onLog = { refreshLog() }

        section("版本")
        note("Vania ${BuildConfig.VERSION_NAME}（构建 ${BuildConfig.BUILD_NUMBER}）")
    }

    override fun onDestroy() {
        if (app.onLog != null) app.onLog = null
        super.onDestroy()
    }

    private fun refreshLog() {
        logView.text = if (app.logs.isEmpty()) "还没有动作" else app.logs.toList().takeLast(60).joinToString("\n")
    }

    // ───────── 小工具 ─────────

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun section(title: String) {
        col.addView(TextView(this).apply {
            text = title; textSize = 13f; setTextColor(0xFFFF7A17.toInt()); typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(20), 0, dp(6))
        })
    }

    private fun label(text: String) {
        col.addView(TextView(this).apply { this.text = text; textSize = 13f; setTextColor(Color.LTGRAY); setPadding(0, dp(8), 0, 0) })
    }

    private fun note(text: String) {
        col.addView(TextView(this).apply { this.text = text; textSize = 12f; setTextColor(Color.GRAY); setPadding(0, dp(6), 0, 0) })
    }

    private fun field(hint: String, value: String, type: Int, secret: Boolean = false, onChange: (String) -> Unit) {
        val e = EditText(this).apply {
            this.hint = hint
            setText(value)
            inputType = InputType.TYPE_CLASS_TEXT or type
            isSingleLine = true
            textSize = 15f
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { onChange(s?.toString()?.trim() ?: "") }
            })
        }
        if (secret) e.setSelection(e.text.length)
        col.addView(e, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun choice(options: List<Pair<String, String>>, current: String, onChange: (String) -> Unit) {
        val g = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        options.forEachIndexed { i, (id, name) ->
            g.addView(RadioButton(this).apply { this.id = i + 1; text = name; isChecked = id == current })
        }
        g.setOnCheckedChangeListener { _, checked -> options.getOrNull(checked - 1)?.let { onChange(it.first) } }
        col.addView(g)
    }

    private fun spinner(options: List<Pair<String, String>>, current: String, onChange: (String) -> Unit) {
        val sp = Spinner(this)
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options.map { it.second })
        sp.setSelection(options.indexOfFirst { it.first == current }.coerceAtLeast(0))
        var last = current
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                val chosen = options[pos].first
                if (chosen != last) { last = chosen; onChange(chosen) }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        col.addView(sp)
    }
}
