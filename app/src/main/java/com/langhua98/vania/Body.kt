package com.langhua98.vania

import org.json.JSONObject

/// 让大模型控制她的身体：云台、换装、动作、转身、镜头、眼睛。对应 iOS 版的 Body.swift，规则一样。
/// - 普通模式（DeepSeek）：回答里写标签，App 照着做，标签不念出来。
/// - 实时模式（Gemini Live，第 5 批）：工具调用。
/// 云台的角度只有一套：相对正面，右、上为正，单位是度。
object Body {
    // 用的时候再拼（里面要用到下面的动作表；对象初始化时下面的表还没准备好）
    val PERSONA_TAGS: String get() = """
你能控制自己的身体：手机夹在大疆云台上，云台能左右转、上下抬头低头。主人让你转头、看某个方向、抬头低头时，在回答里写动作标签，App 会照着转：
[左转 30] [右转 30] [抬头 15] [低头 15]：在现在的基础上再转这么多度。
[看向 右 20 上 10]：转到相对正面的那个方向（可以只写一个，比如 [看向 左 45]）。[回正]：回到正面。[停]：马上停。
[跟着我]：开始一直面对着主人（用前面的摄像头找主人的脸，手机自己跟，不用你一直转）。[别跟了]：不跟了。主人说「跟着我」「看着我」「别跟了」时用。
下面「云台现在的状态」会告诉你现在朝哪、每个方向还能转多少，不要超过；主人问你朝哪就照实说。比如主人说「往左边看看」，你回答「好，我看看左边。[左转 45]」。状态里说云台没连上时不要写这些标签，直接告诉主人云台没连上。不需要转的时候不要写标签，标签不会被念出来。
你还能换衣服：主人让你换衣服时写 [换装 衣服名]，衣服名只能是：${outfitNames()}。比如「好呀，换上啦。[换装 洋装]」。
你有一个 3D 的身体，能做动作：写 [动作 名字]，名字只能是：${actionNames()}。主人让你跳舞、挥手、比耶时就做；平时表示同意可以 [动作 点头]，不同意可以 [动作 摇头]，一次回答最多一个动作，不要每句都做。
屏幕里的你还能转身：[转身 左 90] [转身 右 45] [转身 回正]（数字是度数，0～180，背对主人是 180）；主人让你转过去、给他看看背后、侧过身时用。
屏幕的镜头也能换：[镜头 全身] [镜头 半身] [镜头 特写] [镜头 自动]；主人想看你全身、想凑近看看你的脸时用。
""".trimIndent()

    /// 关于眼睛的那段提示词。canSee：设置里开没开「允许她用摄像头看」
    fun eyesNote(canSee: Boolean): String {
        if (!canSee) return "你现在看不见东西：主人没有打开「允许她用摄像头看」。主人问你看到了什么就照实说看不见、可以去设置里打开，不要编。"
        return """
你有眼睛：主人问你看到了什么、让你看看某个东西或某个方向、问你他在不在时，在回答里写 [看一眼]（看屏幕朝着的方向，平时主人就在这边）或者 [看一眼 后面]（看手机背面朝着的方向）。
写了以后 App 会拍一张照片，把看到的东西告诉你，你再回答主人；所以这一句只要说「好，我看看」之类的话加上标签，不要先猜看到了什么。要看别的方向就先写转动的标签再写 [看一眼]，比如「我看看左边。[左转 45][看一眼]」。
只说 App 告诉你的东西；说得很粗、看不清的时候照实说，不要编细节。没看过就不要说自己看到了什么。
""".trimIndent()
    }

    /// 实时模式（Gemini Live）的提示词：用工具，不写标签
    val PERSONA_TOOL: String get() = """
你能控制自己的身体：手机夹在大疆云台上，云台能左右转、上下抬头低头。主人让你转头、看某个方向、抬头低头、回到正面、停下时，调用 gimbal 工具真的去转，再告诉主人。
gimbal 的角度是相对正面的度数，右和上为正：action 用 turn 是在现在的基础上再转（right: -30 就是再往左 30°），用 look 是转到那个方向（right: 20, up: 10），center 回正面，stop 马上停，status 只问状态。
主人说「跟着我」「一直看着我」时用 follow_on：手机会用前面的摄像头找主人的脸，自己一直面对着主人，不用你一直转；主人说「别跟了」时用 follow_off。
每次调用的结果都会告诉你现在朝哪、每个方向还能转多少，下次不要超过；结果说没连上、到头了、被挡住时照实告诉主人，不要假装转了。
你还能换衣服：主人让你换衣服时调用 change_outfit 工具，衣服名只能是：${outfitNames()}；主人没说换哪套就换一套和现在不一样的。
你有一个 3D 的身体，能做动作：主人让你跳舞、挥手、比耶、点头、摇头等时，调用 play_action 工具，动作名只能是：${actionNames()}。
屏幕里的你还能转身：主人让你转过去、侧过身、给他看看背后时，调用 turn_body 工具。
屏幕的镜头也能换：主人想看你全身、半身，或者凑近看你的脸时，调用 set_camera 工具。
""".trimIndent()

    /// 实时模式的眼睛提示词
    fun eyesNoteTool(canSee: Boolean): String {
        if (!canSee) return eyesNote(false)
        return """
你有眼睛：主人问你看到了什么、让你看看某个东西或某个方向、问你他在不在时，调用 see 工具拍一张照片，结果里会告诉你看到了什么，再照着结果回答。
camera 用 front 是屏幕朝着的方向（平时主人就在这边），用 back 是手机背面朝着的方向。要看别的方向就先用 gimbal 转过去再 see；找东西可以转几个方向各看一眼，最多看 4 次。
只说结果里有的东西；结果说得很粗、看不清的时候照实说，不要编细节。没调用 see 就不要说自己看到了什么。
""".trimIndent()
    }

    /// 给 Gemini Live 的工具声明
    fun toolDeclaration(): JSONObject {
        fun fn(name: String, desc: String, props: JSONObject, required: List<String>) = JSONObject().put("name", name).put("description", desc)
            .put("parameters", JSONObject().put("type", "OBJECT").put("properties", props).apply { if (required.isNotEmpty()) put("required", org.json.JSONArray(required)) })
        fun str(desc: String, enum: List<String>? = null) = JSONObject().put("type", "STRING").put("description", desc).apply { enum?.let { put("enum", org.json.JSONArray(it)) } }
        fun num(desc: String) = JSONObject().put("type", "NUMBER").put("description", desc)
        val fns = org.json.JSONArray()
            .put(fn("gimbal", "转动机器人的身体（大疆云台）。角度是相对正面的度数，右和上为正。结果里有现在的朝向和每个方向还能转多少。", JSONObject()
                .put("action", str("turn 在现在的基础上再转；look 转到相对正面的那个方向；center 回正面；stop 马上停；status 只问现在的状态；follow_on 开始一直面对着主人；follow_off 不跟了",
                    listOf("turn", "look", "center", "stop", "status", "follow_on", "follow_off")))
                .put("right", num("左右的度数，右为正、左为负。turn 时是再转多少，look 时是转到哪。不动就不填"))
                .put("up", num("上下的度数，抬头为正、低头为负。不动就不填")), listOf("action")))
            .put(fn("see", "用摄像头看一眼：拍一张照片，结果里用文字告诉你看到了什么。", JSONObject()
                .put("camera", str("front 屏幕朝着的方向（主人平时在这边），back 手机背面朝着的方向。不填就是 front", listOf("front", "back"))), emptyList()))
            .put(fn("move_gimbal", "（旧的，尽量用 gimbal）转动机器人的身体：左右转、抬头低头，或者回到正面。", JSONObject()
                .put("direction", str("left 左转，right 右转，up 抬头，down 低头，center 回到正面", listOf("left", "right", "up", "down", "center")))
                .put("degrees", num("转多少度：左右 5～90，上下 5～45；center 不用填")), listOf("direction")))
            .put(fn("change_outfit", "给机器人换衣服（3D 角色换装）。", JSONObject()
                .put("name", str("衣服名", Store.OUTFITS.map { it.name })), listOf("name")))
            .put(fn("play_action", "让机器人的 3D 身体做一个动作：挥手、点头、摇头、跳舞、比耶等。", JSONObject()
                .put("name", str("动作名", ACTIONS.map { it.first })), listOf("name")))
            .put(fn("turn_body", "让屏幕里的 3D 身体转身（不是云台）：向左、向右转多少度，背对主人，或者转回来面对主人。", JSONObject()
                .put("direction", str("left 向自己的左边转，right 向右边转，back 背对主人，center 转回来面对主人", listOf("left", "right", "back", "center")))
                .put("degrees", num("转多少度，0～180；back、center 不用填")), listOf("direction")))
            .put(fn("set_camera", "换屏幕的镜头：全身、半身、脸部特写，或者交给自动。", JSONObject()
                .put("shot", str("景别", SHOTS.map { it.name })), listOf("shot")))
        return JSONObject().put("functionDeclarations", fns)
    }

    fun outfitNames() = Store.OUTFITS.joinToString("、") { it.name }
    fun actionNames() = ACTIONS.joinToString("、") { it.first }

    /// 3D 角色会做的动作：中文名 → 发给脸的名字（网页 motion.js 的 ACTIONS）
    val ACTIONS = listOf(
        "挥手" to "wave", "点头" to "nod", "摇头" to "shake", "跳舞" to "dance", "比耶" to "peace", "飞吻" to "kiss",
        "生气" to "angry", "玩手机" to "phone", "打游戏" to "play", "看报纸" to "read", "吃爆米花" to "popcorn",
        "摆姿势" to "pose", "打电话" to "call", "边吃边聊" to "Popcorn_talking",
        "慢舞" to "dance_90bpm", "快舞" to "dance_120bpm", "律动" to "f_vibe_dance_60_bpm_002", "转圈舞" to "vdance",
        "摸头" to "touch_head", "摸左手" to "touch_hand_l", "摸右手" to "touch_hand_r", "摸左腿" to "touch_leg_l", "摸右腿" to "touch_leg_r",
    )

    /// 镜头的景别：中文名、id、叫法（网页 camera.js 的 SHOTS）
    data class Shot(val name: String, val id: String, val words: List<String>)
    val SHOTS = listOf(
        Shot("自动", "auto", listOf("自动", "默认")),
        Shot("全身", "full", listOf("全身", "远景", "远一点", "拉远")),
        Shot("半身", "bust", listOf("半身", "近景", "中景")),
        Shot("特写", "face", listOf("特写", "脸", "大头", "凑近", "拉近")),
    )
    fun shotNames() = SHOTS.joinToString("、") { it.name }

    fun shotID(named: String): String? {
        val text = Store.simplified(named).lowercase()
        SHOTS.firstOrNull { it.id == text }?.let { return it.id }
        return SHOTS.firstOrNull { s -> s.words.any { text.contains(it) } }?.id
    }

    fun actionID(named: String): String? {
        val text = Store.simplified(named)
        ACTIONS.firstOrNull { it.second == text.lowercase() }?.let { return it.second }
        return ACTIONS.firstOrNull { text.contains(it.first) }?.second
    }

    /// 转身：方向（left 她的左边 = 屏幕右边，right，back 背对，center 回正）+ 度数 → 脸的朝向（度，正 = 转向屏幕右边）
    fun turnYaw(direction: String, degrees: Double?): Double = when (direction) {
        "left" -> (degrees ?: 90.0).coerceIn(0.0, 180.0)
        "right" -> -(degrees ?: 90.0).coerceIn(0.0, 180.0)
        "back" -> 180.0
        else -> 0.0
    }

    // ───────── 快捷菜单和短指令 ─────────

    /// 菜单的一项：名字 + 发给脸的消息；msg 是 null 的当成一句话交给 Talk（换装、换背景）
    data class MenuItem(val name: String, val msg: JSONObject?)
    data class MenuGroup(val title: String, val items: List<MenuItem>)

    private fun obj(vararg kv: Pair<String, Any>) = JSONObject().apply { kv.forEach { put(it.first, it.second) } }

    val MENU: List<MenuGroup> by lazy {
        fun act(names: List<String>) = names.map { n -> MenuItem(n, obj("type" to "action", "name" to (ACTIONS.firstOrNull { it.first == n }?.second ?: n))) }
        fun emo(name: String, id: String) = MenuItem(name, if (id == "neutral") obj("type" to "robot_state", "emotion" to id)
            else obj("type" to "robot_state", "emotion" to id, "duration_ms" to 5000))
        listOf(
            MenuGroup("动作", act(listOf("挥手", "点头", "摇头", "比耶", "飞吻", "摆姿势", "生气"))),
            MenuGroup("舞蹈", act(listOf("跳舞", "慢舞", "快舞", "律动", "转圈舞"))),
            MenuGroup("道具", act(listOf("玩手机", "打电话", "打游戏", "看报纸", "吃爆米花", "边吃边聊"))),
            MenuGroup("互动", act(listOf("摸头", "摸左手", "摸右手", "摸左腿", "摸右腿"))),
            MenuGroup("表情", listOf(emo("开心", "happy"), emo("惊讶", "surprised"), emo("疑惑", "confused"), emo("警觉", "alert"), emo("平静", "neutral"))),
            MenuGroup("镜头", SHOTS.map { MenuItem(it.name, obj("type" to "view", "shot" to it.id)) } + listOf(
                MenuItem("正面", obj("type" to "view", "orbit" to 0, "tilt" to 0)),
                MenuItem("左侧", obj("type" to "view", "orbit" to -45)), MenuItem("右侧", obj("type" to "view", "orbit" to 45)),
                MenuItem("背面", obj("type" to "view", "orbit" to 180)),
                MenuItem("俯视", obj("type" to "view", "tilt" to 25)), MenuItem("仰视", obj("type" to "view", "tilt" to -15)), MenuItem("平视", obj("type" to "view", "tilt" to 0)),
            )),
            MenuGroup("转身", listOf(
                MenuItem("向左转", obj("type" to "turn", "yaw" to 90)), MenuItem("向右转", obj("type" to "turn", "yaw" to -90)),
                MenuItem("左转一点", obj("type" to "turn", "by" to 30)), MenuItem("右转一点", obj("type" to "turn", "by" to -30)),
                MenuItem("背对", obj("type" to "turn", "yaw" to 180)), MenuItem("转回来", obj("type" to "turn", "yaw" to 0)),
            )),
            MenuGroup("视线", listOf(
                MenuItem("看左边", obj("type" to "look", "x" to -0.9, "y" to 0, "ms" to 8000)), MenuItem("看右边", obj("type" to "look", "x" to 0.9, "y" to 0, "ms" to 8000)),
                MenuItem("看上面", obj("type" to "look", "x" to 0, "y" to -0.9, "ms" to 8000)), MenuItem("看下面", obj("type" to "look", "x" to 0, "y" to 0.9, "ms" to 8000)),
                MenuItem("看我", obj("type" to "look", "free" to true)),
            )),
            MenuGroup("云台", GIMBAL_MENU.map { MenuItem(it.first, bodyMsg(it.second)) }),
            MenuGroup("换装", Store.OUTFITS.map { MenuItem("换上" + it.name, null) }),
            MenuGroup("背景", Store.BACKGROUNDS.map { MenuItem("背景 " + it.name, null) }),
            MenuGroup("其他", listOf(MenuItem("停下", obj("type" to "body", "action" to "stop")), MenuItem("重置", obj("type" to "reset")))),
        )
    }

    data class Quick(val msg: JSONObject, val label: String)

    /// 聊天框里打的短指令，直接做、不问大模型（没填密钥也能用）。普通的话返回 null，交给大模型
    fun quickCommand(raw: String): Quick? {
        var t = Store.simplified(raw)
        if (t.startsWith("/") || t.startsWith("／")) t = t.drop(1)
        for (g in MENU) g.items.firstOrNull { it.name == t }?.msg?.let { return Quick(it, "${g.title}：$t") }
        if (t in listOf("重置", "复位", "回到默认")) return Quick(obj("type" to "reset"), "回到默认")
        val bare = t.lowercase().trim { it in "。！!.，,？? " }   // 语音识别会带标点
        if (bare in listOf("停", "停下", "别动", "停下来", "别转了", "不要动", "stop")) return Quick(obj("type" to "body", "action" to "stop"), "停下")
        if (bare in listOf("别跟了", "不跟了", "不要跟了", "别跟着我", "别看着我了")) return Quick(obj("type" to "body", "action" to "follow_off"), "别跟了")
        if (t.startsWith("云台")) {
            gimbalMoves("[" + t.drop(2).trim() + "]").firstOrNull()?.let { return Quick(bodyMsg(it), "云台：" + describe(it)) }
        }
        ACTIONS.firstOrNull { it.first == t || it.second == t.lowercase() }?.let { return Quick(obj("type" to "action", "name" to it.second), "动作：${it.first}") }
        if (t.startsWith("动作")) actionID(t.drop(2))?.let { return Quick(obj("type" to "action", "name" to it), "动作：${t.drop(2)}") }
        SHOTS.firstOrNull { it.name == t || "镜头" + it.name == t || it.name + "镜头" == t }?.let { return Quick(obj("type" to "view", "shot" to it.id), "镜头：${it.name}") }
        if (t.startsWith("镜头")) shotID(t.drop(2))?.let { return Quick(obj("type" to "view", "shot" to it), "镜头：${t.drop(2)}") }
        val turnWords = mapOf("背对" to "转身背对", "转回来" to "转身回正", "回正" to "转身回正")
        if (t.startsWith("转身") || turnWords[t] != null) {
            turnTags("[" + (turnWords[t] ?: t) + "]").firstOrNull()?.let { yaw ->
                return Quick(obj("type" to "turn", "yaw" to yaw), if (yaw == 0.0) "转身：回正" else "转身：${yaw.toInt()}°")
            }
        }
        return null
    }

    // ───────── 回答里的标签 ─────────

    private val OUTFIT_RE = Regex("""\[\s*换装\s*[:：]?\s*([^\]]+?)\s*\]""")
    private val ACTION_RE = Regex("""\[\s*动作\s*[:：]?\s*([^\]]+?)\s*\]""")
    private val VIEW_RE = Regex("""\[\s*镜头\s*[:：]?\s*([^\]]+?)\s*\]""")
    private val TURN_RE = Regex("""\[\s*转身\s*[:：]?\s*(左|右|背对|回正|回来)?\s*(\d+(?:\.\d+)?)?\s*度?\s*\]""")
    private val GIMBAL_RE = Regex("""\[\s*(左转|右转|抬头|低头|回正|回中|停下|停|别动|设为正面|跟着我|看着我|别跟了|不跟了)\s*(\d+(?:\.\d+)?)?\s*度?\s*\]""")
    private val LOOK_RE = Regex("""\[\s*看向\s*[:：]?\s*([^\]]*?)\s*\]""")
    private val LOOK_PART = Regex("""(左|右|上|下)\s*(\d+(?:\.\d+)?)""")
    private val SEE_RE = Regex("""\[\s*看一眼\s*[:：]?\s*(前面|后面|背面|前|后)?\s*\]""")

    fun outfitTags(text: String) = OUTFIT_RE.findAll(text).map { it.groupValues[1] }.toList()
    fun actionTags(text: String) = ACTION_RE.findAll(text).map { it.groupValues[1] }.toList()
    fun viewTags(text: String) = VIEW_RE.findAll(text).map { it.groupValues[1] }.toList()

    fun turnTags(text: String): List<Double> = TURN_RE.findAll(text).map { m ->
        val verb = m.groupValues[1]
        val deg = m.groupValues[2].toDoubleOrNull()
        val dir = mapOf("左" to "left", "右" to "right", "背对" to "back")[verb] ?: if (deg != null && verb.isEmpty()) "left" else "center"
        turnYaw(dir, deg)
    }.toList()

    /// [看一眼]：true 用前摄，false 用后摄，null 没写
    fun seeTag(text: String): Boolean? {
        val m = SEE_RE.find(text) ?: return null
        val which = m.groupValues[1]
        return !(which.startsWith("后") || which.startsWith("背"))
    }

    // ───────── 云台 ─────────

    /// 云台的一次动作。action：turn 再转多少 / look 转到哪 / center 回正 / stop 停 / status 只问状态 / home 把现在记成正面 / follow_on / follow_off
    data class GimbalMove(val action: String, val right: Double? = null, val up: Double? = null)

    val GIMBAL_MENU = listOf(
        "云台左转" to GimbalMove("turn", right = -30.0), "云台右转" to GimbalMove("turn", right = 30.0),
        "云台抬头" to GimbalMove("turn", up = 15.0), "云台低头" to GimbalMove("turn", up = -15.0),
        "云台回正" to GimbalMove("center"), "云台停" to GimbalMove("stop"), "云台设为正面" to GimbalMove("home"),
        "跟着我" to GimbalMove("follow_on"), "别跟了" to GimbalMove("follow_off"),
    )

    /// 开关「跟着人转」（第 4 批填上）。返回一句给模型 / 记录看的结果
    var followSwitch: ((Boolean) -> String)? = null

    fun gimbalMoves(text: String): List<GimbalMove> {
        val found = mutableListOf<Pair<Int, GimbalMove>>()
        for (m in GIMBAL_RE.findAll(text)) {
            val deg = m.groupValues[2].toDoubleOrNull()
            val move = when (m.groupValues[1]) {
                "左转" -> GimbalMove("turn", right = -(deg ?: 30.0))
                "右转" -> GimbalMove("turn", right = deg ?: 30.0)
                "抬头" -> GimbalMove("turn", up = deg ?: 15.0)
                "低头" -> GimbalMove("turn", up = -(deg ?: 15.0))
                "回正", "回中" -> GimbalMove("center")
                "设为正面" -> GimbalMove("home")
                "跟着我", "看着我" -> GimbalMove("follow_on")
                "别跟了", "不跟了" -> GimbalMove("follow_off")
                else -> GimbalMove("stop")
            }
            found += m.range.first to move
        }
        for (m in LOOK_RE.findAll(text)) {
            var right: Double? = null
            var up: Double? = null
            for (p in LOOK_PART.findAll(m.groupValues[1])) {
                val v = p.groupValues[2].toDoubleOrNull() ?: 0.0
                when (p.groupValues[1]) { "左" -> right = -v; "右" -> right = v; "上" -> up = v; else -> up = -v }
            }
            // 「看向 正面」这种没有数字的：回正
            found += m.range.first to if (right == null && up == null) GimbalMove("center") else GimbalMove("look", right, up)
        }
        return found.sortedBy { it.first }.map { it.second }
    }

    fun bodyMsg(m: GimbalMove) = JSONObject().apply {
        put("type", "body"); put("action", m.action)
        m.right?.let { put("right", it) }
        m.up?.let { put("up", it) }
    }

    fun gimbalMove(msg: JSONObject) = GimbalMove(
        msg.optString("action", "status"),
        if (msg.has("right")) msg.optDouble("right") else null,
        if (msg.has("up")) msg.optDouble("up") else null,
    )

    /// 旧工具 move_gimbal(direction, degrees) → 新的动作
    fun gimbalMove(direction: String, degrees: Double?) = when (direction) {
        "left" -> GimbalMove("turn", right = -(degrees ?: 30.0))
        "right" -> GimbalMove("turn", right = degrees ?: 30.0)
        "up" -> GimbalMove("turn", up = degrees ?: 15.0)
        "down" -> GimbalMove("turn", up = -(degrees ?: 15.0))
        "stop" -> GimbalMove("stop")
        else -> GimbalMove("center")
    }

    fun describe(m: GimbalMove): String {
        fun part(v: Double?, pos: String, neg: String) = v?.let { "${if (it >= 0) pos else neg} ${Math.abs(it).toInt()}°" } ?: ""
        val where = listOf(part(m.right, "右", "左"), part(m.up, "上", "下")).filter { it.isNotEmpty() }.joinToString(" ")
        return when (m.action) {
            "turn" -> "再转 $where"
            "look" -> "看向 $where"
            "center" -> "回正"
            "stop" -> "停"
            "home" -> "设为正面"
            "follow_on" -> "跟着我"
            "follow_off" -> "别跟了"
            else -> "问状态"
        }
    }

    /// 真的去转。返回一句给模型 / 记录看的结果，里面带现在的朝向和还能转多少
    suspend fun perform(gimbal: Gimbal, m: GimbalMove, who: Gimbal.Owner = Gimbal.Owner.MODEL): String {
        // 模型偶尔会给出离谱的数字，先收一下（真正的限位在 Gimbal.aim 里）
        val right = m.right?.coerceIn(-360.0, 360.0)
        val up = m.up?.coerceIn(-90.0, 90.0)
        return when (m.action) {
            "turn" -> gimbal.aim(right, up, relative = true, who = who)
            "look" -> gimbal.aim(right, up, relative = false, who = who)
            "center" -> gimbal.aim(0.0, 0.0, relative = false, who = who)
            "stop" -> { gimbal.halt(); "停了。" + gimbal.stateText() }
            "follow_on", "follow_off" -> followSwitch?.invoke(m.action == "follow_on") ?: "这台手机上还没有跟随功能。"
            "home" -> if (!gimbal.connected) gimbal.stateText() else { gimbal.setHomeHere(); "已把现在的朝向记成正面。" }
            else -> gimbal.stateText()
        }
    }

    /// 去掉标签（念出来之前）
    fun strip(text: String): String = listOf(GIMBAL_RE, LOOK_RE, SEE_RE, OUTFIT_RE, ACTION_RE, VIEW_RE, TURN_RE)
        .fold(text) { t, re -> re.replace(t, "") }
}
