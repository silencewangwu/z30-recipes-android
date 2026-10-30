package com.z30lab.core

import java.io.ByteArrayOutputStream

/** PTP/IP packet: 8-byte header (length + type) followed by payload. */
class PTPPacket(val type: Long, val payload: ByteArray) {
    val encoded: ByteArray by lazy {
        val out = ByteArrayOutputStream()
        put32(out, 8L + payload.size)
        put32(out, type)
        out.write(payload)
        out.toByteArray()
    }

    companion object {
        fun operation(code: Int, transaction: Long, parameters: List<Long> = emptyList(), dataPhase: Long = 1): PTPPacket {
            val p = ByteArrayOutputStream()
            put32(p, dataPhase)
            put16(p, code)
            put32(p, transaction)
            parameters.forEach { put32(p, it) }
            return PTPPacket(6L, p.toByteArray())
        }
    }
}

/** Incremental PTP/IP stream framer; handles fragmented and coalesced packets. */
class PacketFramer {
    companion object {
        const val maxPacketBytes = 8 * 1024 * 1024
    }

    private var buffer = ByteArray(0)

    fun append(data: ByteArray): List<PTPPacket> {
        buffer = buffer + data
        val packets = mutableListOf<PTPPacket>()
        while (buffer.size >= 8) {
            val reader = BinaryReader(buffer)
            val length = reader.u32()
            val type = reader.u32()
            require(length >= 8 && length <= maxPacketBytes) { throw CameraError.Invalid("PTP/IP 包长度无效") }
            if (buffer.size < length) break
            packets.add(PTPPacket(type, buffer.slice(8, (length - 8).toInt())))
            buffer = buffer.slice(length.toInt(), buffer.size - length.toInt())
        }
        return packets
    }
}

/** Assembles a PTP data transaction from start / chunk / end / response packets. */
class PTPDataTransaction(val id: Long) {
    private var expected: Long? = null // -1L represents UInt64.max (unknown size)
    private var ended = false
    private val output = ByteArrayOutputStream()

    fun consume(packet: PTPPacket): ByteArray? {
        val r = BinaryReader(packet.payload)
        when (packet.type) {
            9L -> {
                require(expected == null && !ended && r.u32() == id) {
                    throw CameraError.Invalid("数据开始包或事务编号错误")
                }
                val size = r.u64()
                require(r.offset == packet.payload.size && (size == -1L || size <= PacketFramer.maxPacketBytes)) {
                    throw CameraError.Invalid("读取数据过大或开始包格式错误")
                }
                expected = size
            }
            10L, 12L -> {
                require(expected != null && !ended && r.u32() == id) {
                    throw CameraError.Invalid("数据分段顺序或事务编号错误")
                }
                val chunk = r.bytes(packet.payload.size - r.offset)
                require(chunk.size <= PacketFramer.maxPacketBytes - output.size()) {
                    throw CameraError.Invalid("读取数据过大")
                }
                output.write(chunk)
                val exp = expected
                if (exp != null && exp != -1L && output.size().toLong() > exp) {
                    throw CameraError.Invalid("数据超出声明长度")
                }
                if (packet.type == 12L) {
                    ended = true
                    require(expected == -1L || output.size().toLong() == expected) {
                        throw CameraError.Invalid("数据未传输完整")
                    }
                }
            }
            7L -> {
                val status = r.u16()
                require(r.u32() == id) { throw CameraError.Invalid("响应事务编号错误") }
                require(status == 0x2001) { throw CameraError.Response(status) }
                require(expected == null || ended) { throw CameraError.Invalid("缺少数据结束包") }
                return output.toByteArray()
            }
            else -> throw CameraError.Invalid("命令通道收到意外的数据包 ${packet.type}")
        }
        return null
    }
}

/** Parsed camera DeviceInfo (0x1001 response). */
class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val firmware: String,
    val operations: IntArray,
    val identityFingerprint: String?
) {
    companion object {
        fun parse(data: ByteArray): DeviceInfo {
            val r = BinaryReader(data)
            r.u16(); r.u32(); r.u16(); r.string(); r.u16()
            val operations = r.array16()
            repeat(4) { r.array16() }
            val manufacturer = r.string()
            val model = r.string()
            val firmware = r.string()
            val serial = r.string()
            val identityFingerprint = if (serial.isEmpty()) null else {
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest("$manufacturer\u0000$model\u0000$serial".toByteArray(Charsets.UTF_8))
                digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            }
            return DeviceInfo(manufacturer, model, firmware, operations, identityFingerprint)
        }
    }
}

/** Parsed Picture Control property descriptor (0x1014 / D200). */
class PictureControlDescriptor(val writable: Boolean, val current: Int, val choices: List<Int>) {
    companion object {
        fun parse(data: ByteArray): PictureControlDescriptor {
            val r = BinaryReader(data)
            require(r.u16() == 0xD200 && r.u16() == 4) { throw CameraError.Invalid("优化校准属性类型不符") }
            val writable = r.u8() == 1
            r.u16()
            val current = r.u16()
            val form = r.u8()
            val choices = when (form) {
                2 -> {
                    val count = r.u16()
                    (0 until count).map { r.u16() }
                }
                0 -> emptyList()
                else -> throw CameraError.Invalid("未识别的优化校准属性表")
            }
            return PictureControlDescriptor(writable, current, choices)
        }
    }
}
