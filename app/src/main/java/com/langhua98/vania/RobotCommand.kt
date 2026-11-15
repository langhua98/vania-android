package com.langhua98.vania

import org.json.JSONObject

/// 遥控指令 {"do":"robot", "action": …}：从另一台设备控制屏幕里的 3D 角色。对应 iPhone 版的 RobotCommand.swift，
/// 动作、镜头、转身、视线、表情、换装、背景、说话都能控制，回执里带 robot（现在的状态）。只在主线程调用。
object RobotCommand {
    const val ACTIONS_HELP = "status、act、view、turn、look、emotion、outfit、bg、say、reset"

    suspend fun run(cmd: JSONObject, face: FaceBridge, talk: Talk, store: Store): CmdResult {
        val action = cmd.optString("action", "status")
        if (!face.isReady) return CmdResult("机器人的脸没打开（App 不在前台，或者页面还在加载）")
        fun str(k: String) = cmd.opt(k)?.toString()?.trim()?.takeIf { it != "null" } ?: ""
        fun num(k: String): Double? = when (val v = cmd.opt(k)) { is Number -> v.toDouble(); is String -> v.toDoubleOrNull(); else -> null }
        return when (action) {
            "status" -> CmdResult(describe(face))

            "act" -> {
                val raw = str("name")
                if (raw.isEmpty()) return CmdResult("act 要带 name，可以是：${Body.actionNames()}，或者英文名、动画名")
                val name = Body.actionID(raw) ?: raw
                val ok = face.request(JSONObject().put("type", "action").put("name", name)) as? Boolean ?: false
                CmdResult(if (ok) "在做：$raw" else "不会这个动作：$raw（会的：${Body.actionNames()}）")
            }

            "view" -> {
                val msg = JSONObject().put("type", "view")
                val shotRaw = str("shot")
                if (shotRaw.isNotEmpty()) {
                    val shot = Body.shotID(shotRaw) ?: return CmdResult("没有这种镜头：$shotRaw（有：${Body.shotNames()}，或者 auto、full、bust、face）")
                    msg.put("shot", shot)
                }
                num("orbit")?.let { msg.put("orbit", it) }
                num("tilt")?.let { msg.put("tilt", it) }
                if (msg.length() <= 1) return CmdResult("view 要带 shot、orbit、tilt 里至少一个")
                val r = face.request(msg) as? JSONObject ?: JSONObject()
                CmdResult("镜头：${shotName(r.optString("shot"))}，绕 ${r.optDouble("orbit", 0.0).toInt()}°，俯仰 ${r.optDouble("tilt", 0.0).toInt()}°")
            }

            "turn" -> {
                val msg = JSONObject().put("type", "turn")
                when {
                    num("by") != null -> msg.put("by", num("by"))
                    num("yaw") != null -> msg.put("yaw", num("yaw"))
                    str("direction").isNotEmpty() -> msg.put("yaw", Body.turnYaw(str("direction"), num("degrees")))
                    else -> return CmdResult("turn 要带 yaw（转到多少度）、by（再转多少度）或者 direction（left、right、back、center）")
                }
                val r = face.request(msg) as? JSONObject ?: JSONObject()
                val yaw = Math.round(r.optDouble("yaw", 0.0)).toInt()
                CmdResult(if (yaw == 0) "面向屏幕" else "转到 $yaw°（正 = 转向屏幕右边）")
            }

            "look" -> {
                val msg = JSONObject().put("type", "look")
                if (cmd.optBoolean("free")) msg.put("free", true)
                else {
                    msg.put("x", num("x") ?: 0.0).put("y", num("y") ?: 0.0)
                    num("ms")?.let { msg.put("ms", it) }
                }
                face.request(msg)
                CmdResult(if (msg.has("free")) "视线放开了" else "看向 x=${msg.opt("x")}，y=${msg.opt("y")}")
            }

            "emotion" -> {
                val name = str("name")
                if (name !in Chat.EMOTIONS) return CmdResult("emotion 的 name 只能是：${Chat.EMOTIONS.joinToString("、")}")
                val msg = JSONObject().put("type", "robot_state").put("emotion", name)
                num("ms")?.takeIf { it > 0 }?.let { msg.put("duration_ms", it) }
                face.send(msg)
                CmdResult("表情：$name")
            }

            "outfit" -> {
                val raw = str("id").ifEmpty { str("name") }
                val id = Store.outfitID(raw) ?: return CmdResult("没有这套衣服：$raw（有：${Store.OUTFITS.joinToString("、") { "${it.name}（${it.id}）" }}）")
                store.outfit = id
                CmdResult("换装：${Store.outfitName(id)}")
            }

            "bg" -> {
                val raw = str("id").ifEmpty { str("name") }
                val b = Store.BACKGROUNDS.firstOrNull { it.id == raw || it.name == raw }?.id ?: Store.backgroundCommand("背景 $raw")
                    ?: return CmdResult("没有这个背景：$raw（有：${Store.BACKGROUNDS.joinToString("、") { "${it.name}（${it.id}）" }}）")
                store.background = b
                CmdResult("背景：${Store.backgroundName(b)}")
            }

            "say" -> {
                val text = str("text")
                if (text.isEmpty()) return CmdResult("say 要带 text")
                val ok = talk.speak(text, str("emotion").ifEmpty { "neutral" })
                CmdResult(if (ok) "在说：$text" else "她正在实时语音聊天，没有插话")
            }

            "reset" -> {
                face.request(JSONObject().put("type", "reset"))
                CmdResult("回到默认：自动镜头、面向屏幕、平静")
            }

            else -> CmdResult("robot 不认识的 action：$action（可以用：$ACTIONS_HELP）")
        }
    }

    /// 回执里的 robot 字段：现在的状态（去掉动画名单，太长）
    suspend fun status(face: FaceBridge): JSONObject? {
        val s = face.status() ?: return null
        s.remove("clips"); s.remove("stats")
        return s
    }

    private suspend fun describe(face: FaceBridge): String {
        val s = face.status() ?: return "读不到状态"
        val view = s.optJSONObject("view") ?: JSONObject()
        val lines = mutableListOf(
            "衣服：${Store.outfitName(s.optString("outfit"))}",
            "背景：${Store.backgroundName(s.optString("bgWant", "auto"))}",
            "状态：${s.optString("state")}，表情：${s.optString("emotion")}",
            "镜头：${shotName(view.optString("shot"))}，绕 ${view.optDouble("orbit", 0.0).toInt()}°，俯仰 ${view.optDouble("tilt", 0.0).toInt()}°",
            "朝向：${s.optDouble("turn", 0.0).toInt()}°",
        )
        s.optString("action").takeIf { it.isNotEmpty() }?.let { lines += "正在做：$it" }
        return lines.joinToString("\n")
    }

    private fun shotName(id: String?) = Body.SHOTS.firstOrNull { it.id == id }?.name ?: (id ?: "?")
}
