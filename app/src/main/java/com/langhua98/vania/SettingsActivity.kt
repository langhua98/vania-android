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
import android.Manifest
import kotlinx.coroutines.launch
import android.widget.Button
import android.widget.Switch
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

        note("这台手机开「待命」后，等另一台设备通过小橘云盘发来的指令：拍照、录音、定位、手电筒、震动、响铃，还能遥控屏幕里的机器人（robot 指令）。待命时通知栏一直显示「Vania 待命中」，动作都记在最下面。拍照、录音、定位要 Vania 在前台。")
        section("云盘")
        field("云盘地址", s.base, InputType.TYPE_TEXT_VARIATION_URI) { s.base = it }
        field("云盘令牌 xa_…", s.token, InputType.TYPE_TEXT_VARIATION_PASSWORD, secret = true) { s.token = it }
        section("这台设备")
        field("设备名，比如 测试机B", s.device, 0) { s.device = it }
        field("配对口令（两台要一样）", s.pair, InputType.TYPE_TEXT_VARIATION_PASSWORD, secret = true) { s.pair = it }
        button("授权硬件（相机 / 麦克风 / 定位 / 通知）") {
            requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS) + app.gimbal.permissions(), 2)
        }
        standbyBtn = button(standbyLabel()) { if (app.running) app.stopStandby() else app.startStandby() }
        toggle("打开 App 时自动待命", s.autoStandby) { s.autoStandby = it }
        standbyNote = TextView(this).apply { textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(6), 0, 0) }
        col.addView(standbyNote)
        refreshStandby()
        app.onStandby += standbyListener

        gimbalSection()

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
        note("安卓版是照着 iPhone 版 Vania 一批一批做的。这一版有：3D 角色、聊天框、快捷菜单、大模型对话、她说话、换装、背景、镜头、动作、云盘待命和遥控指令、大疆云台。还没有：跟着人转、眼睛、Gemini 实时语音，后面几版加上。")

        section("记录")
        logView = TextView(this).apply { textSize = 11f; typeface = Typeface.MONOSPACE; setTextColor(0xFFCCCCCC.toInt()) }
        col.addView(logView)
        refreshLog()
        app.onLog = { refreshLog() }

        section("版本")
        note("Vania ${BuildConfig.VERSION_NAME}（构建 ${BuildConfig.BUILD_NUMBER}）")
    }

    override fun onDestroy() {
        app.onLog = null
        app.onStandby -= standbyListener
        app.gimbal.onChange -= gimbalListener
        super.onDestroy()
    }

    private val standbyListener: () -> Unit = { refreshStandby() }

    // ───────── 云台 ─────────

    private val gimbalListener: () -> Unit = { refreshGimbal() }
    private lateinit var gimbalState: TextView
    private lateinit var gimbalPads: LinearLayout
    private lateinit var gimbalBtn: Button

    private fun gimbalSection() {
        val g = app.gimbal
        section("云台（大疆 OM）")
        gimbalState = TextView(this).apply { textSize = 13f; setTextColor(Color.LTGRAY) }
        col.addView(gimbalState)
        gimbalPads = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun pad(label: String, act: () -> Unit) = gimbalPads.addView(Button(this).apply { text = label; setOnClickListener { act() } },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        pad("←") { g.manualMove(-25.0, 0.0, 0.6) }
        pad("↑") { g.manualMove(0.0, 20.0, 0.6) }
        pad("↓") { g.manualMove(0.0, -20.0, 0.6) }
        pad("→") { g.manualMove(25.0, 0.0, 0.6) }
        pad("◎") { kotlinx.coroutines.MainScope().launch { g.aim(0.0, 0.0, false, Gimbal.Owner.MANUAL) } }
        col.addView(gimbalPads)
        gimbalBtn = button("连接云台") {
            if (g.connected || g.phase == Gimbal.Phase.SCANNING || g.phase == Gimbal.Phase.CONNECTING) g.disconnect()
            else if (!g.hasPermission()) requestPermissions(g.permissions(), 3)
            else g.connect()
        }
        toggle("打开 App 时自动连", app.store.gimbalAuto) { app.store.gimbalAuto = it }
        toggle("抬头低头反了", g.pitchFlipped) { g.pitchFlipped = it }
        note("把这台手机夹在大疆 OM 云台上，它就是机器人的身体：别的设备可以远程让它转头（gimbal 指令），机器人的脸也跟着云台的朝向看。用之前先在 DJI Mimo 里断开云台。箭头试着转一下，靶心回到正面（连上时的朝向）。她自己也能转：说「往左看看」「看右边 20 度」「回正」「停」，或者在聊天框打「云台 左转 30」。左右最多各转 120°，抬头 35°、低头 20°。点上箭头她却低头的话，打开「抬头低头反了」。")
        g.onChange += gimbalListener
        refreshGimbal()
    }

    private fun refreshGimbal() {
        val g = app.gimbal
        gimbalState.text = "${g.name.ifEmpty { "大疆 OM 云台" }}：${if (g.busy) "正在转" else g.phase.label}" +
            (if (g.connected) "\n" + g.status().lines().drop(1).joinToString("\n") else if (g.note.isNotEmpty()) "\n" + g.note else "")
        gimbalState.setTextColor(if (g.connected) 0xFF34C759.toInt() else Color.LTGRAY)
        gimbalPads.visibility = if (g.connected) android.view.View.VISIBLE else android.view.View.GONE
        gimbalBtn.text = if (g.connected || g.phase == Gimbal.Phase.SCANNING || g.phase == Gimbal.Phase.CONNECTING) "断开云台" else "连接云台"
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 3 && app.gimbal.hasPermission()) app.gimbal.connect()
    }
    private lateinit var standbyBtn: Button
    private lateinit var standbyNote: TextView
    private fun standbyLabel() = if (app.running) "停止待命" else "开始待命"
    private fun refreshStandby() {
        standbyBtn.text = standbyLabel()
        standbyNote.text = if (app.running) "● 待命中（设备名：${app.store.device}）" else "○ 未运行"
        standbyNote.setTextColor(if (app.running) 0xFF34C759.toInt() else Color.GRAY)
    }

    private fun button(text: String, onClick: () -> Unit): Button {
        val b = Button(this).apply { this.text = text; isAllCaps = false; setOnClickListener { onClick() } }
        col.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return b
    }

    private fun toggle(text: String, value: Boolean, onChange: (Boolean) -> Unit) {
        col.addView(Switch(this).apply { this.text = text; isChecked = value; textSize = 15f; setOnCheckedChangeListener { _, v -> onChange(v) } })
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
