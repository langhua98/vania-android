package com.langhua98.vania

/// 大疆 OM 云台。第 3 批再接蓝牙；现在先是一个「没连上」的空壳，让对话里的云台标签有个去处：
/// 她会照实告诉主人云台没连上，不会假装转了。
class Gimbal {
    /// 谁在控制，优先级从低到高
    enum class Owner { NOBODY, FOLLOW, MODEL, REMOTE, MANUAL }

    val connected: Boolean get() = false

    suspend fun aim(right: Double?, up: Double?, relative: Boolean, who: Owner): String =
        "云台没连上，转不了（安卓版还没接云台）。"

    fun halt() {}

    fun setHomeHere() {}

    fun stateText(): String = "云台没连上。"

    /// 遥控指令 {"do":"gimbal", …}
    suspend fun command(cmd: org.json.JSONObject): String = "这台手机还没接云台（安卓版第 3 批再做）。"

    fun statusJSON(): org.json.JSONObject = org.json.JSONObject().put("connected", false).put("phase", "未连接")
}
