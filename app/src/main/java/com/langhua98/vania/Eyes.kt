package com.langhua98.vania

/// 她的眼睛（第 4 批再做：CameraX 拍一张 + ML Kit 在手机里识别，或者交给填好的「眼睛」模型）。
/// 现在先照实告诉模型看不见。
object Eyes {
    /// 看一眼。返回（给大模型看的一段话，给记录用的一句话）
    suspend fun look(front: Boolean, store: Store, face: FaceBridge): Pair<String, String> =
        "这台手机还没有接摄像头的功能，你现在看不见，照实告诉主人。" to "安卓版还没有眼睛"
}
