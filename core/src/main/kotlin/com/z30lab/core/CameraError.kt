package com.z30lab.core

/** Mirrors the iOS CameraError enum: every transport/validation failure maps to a typed error. */
sealed class CameraError(message: String) : Exception(message) {
    class Invalid(reason: String) : CameraError("相机数据异常：$reason")
    class Verification(reason: String) : CameraError("读回尚未确认：$reason")
    class Timeout : CameraError("连接超时。请检查相机 Wi-Fi、IP 地址，并确认 SnapBridge 已断开。")
    class Closed : CameraError("连接已断开，请重新连接相机 Wi-Fi 后重试。")
    class Response(val code: Int) : CameraError(buildMessage(code))

    companion object {
        private fun buildMessage(code: Int): String = when (code) {
            0x2019 -> "相机忙碌，请稍后重试（0x2019）。"
            0x200F -> "相机拒绝此写入（0x200F）；文件数据或当前拍摄状态未被接受。"
            0x2005 -> "相机在当前连接模式下不支持这条指令（0x2005）。"
            else -> String.format("相机返回 0x%04X", code)
        }
    }
}
