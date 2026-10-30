package com.z30lab.core

/** UInt8 → Int semantics: byte value in 0..255 (Swift UInt8 → Int is unsigned). */
fun Byte.u(): Int = this.toInt() and 0xFF

/**
 * Classic NCP storage is distinct from the Z30's PTP wire dataset. Retain the
 * complete original, including the author's LUT, until that wire layout is verified.
 */
class ClassicNcpRecipe(
    val original: ByteArray,
    val name: String,
    val base: Int,
    val settings: ByteArray,
    val curve: ByteArray?
) {
    val baseName: String = BASE_NAMES[base] ?: "未知"
    val transferIssue: String? = null
    val summary: String = "$baseName · " + (curve?.let { "自定义曲线（${it[8].u()} 个控制点）" } ?: "基本参数")

    companion object {
        val wireBaseIDs: Map<Int, Int> = mapOf(
            0x0001 to 1, 0x03C2 to 2, 0x00C3 to 3, 0x064D to 4, 0x0486 to 5, 0x04C7 to 6
        )
        private val BASE_NAMES = mapOf(
            0x0001 to "标准", 0x03C2 to "自然", 0x00C3 to "鲜艳",
            0x0486 to "人像", 0x04C7 to "风景", 0x064D to "单色"
        )
        private val SUPPORTED_BASES = wireBaseIDs.keys
        private val ASCII_0100 = "0100".toByteArray(Charsets.US_ASCII)

        fun parse(data: ByteArray): ClassicNcpRecipe {
            val records = Np3Document.parse(data).records
            require(records.keys == setOf(1L) || records.keys == setOf(1L, 2L)) {
                throw CameraError.Invalid("尚不支持此 NCP 版本或扩展设置")
            }
            val body = records[1] ?: throw CameraError.Invalid("NCP 缺少主体数据")
            require(
                body.size == 36 && body.slice(0, 4).contentEquals(ASCII_0100) &&
                    (body[26].u() == 0 || body[26].u() == 2) &&
                    body[27].u() == 255
            ) { throw CameraError.Invalid("尚不支持此 NCP 版本或扩展设置") }

            val nameData = body.slice(4, 20)
            val end = nameData.indexOfByte(0)
            require(
                end > 0 &&
                    nameData.slice(0, end).all { it.u() in 32..126 } &&
                    nameData.slice(end, nameData.size - end).all { it.toInt() == 0 }
            ) { throw CameraError.Invalid("NCP 名称须为 1–19 个 ASCII 字符") }

            val base = (body[24].u() shl 8) or body[25].u()
            require(SUPPORTED_BASES.contains(base)) { throw CameraError.Invalid("此 NCP 的基础优化校准暂未支持") }

            val usesCurve = body[29].u() == 1 || body[30].u() == 1
            require(
                (body[28].u() == 0 || (128..137).contains(body[28].u())) &&
                    (body[29].u() == 0 || body[29].u() == 1 || (125..131).contains(body[29].u())) &&
                    (body[30].u() == 1 || (127..129).contains(body[30].u())) &&
                    (if (usesCurve) body[29].u() == 1 && body[30].u() == 1 && records.containsKey(2L)
                     else !records.containsKey(2L))
            ) { throw CameraError.Invalid("NCP 参数或曲线标记不完整") }

            if (base == 0x064D) {
                require(
                    body[31].u() == 255 && body[32].u() == 255 &&
                        (128..132).contains(body[33].u()) && (128..137).contains(body[34].u()) &&
                        (body[35].u() == 255 || (128..135).contains(body[35].u()))
                ) { throw CameraError.Invalid("NCP 单色设置超出支持范围") }
            } else {
                require(
                    (body[31].u() == 0 || (125..131).contains(body[31].u())) &&
                        (125..131).contains(body[32].u()) &&
                        body.slice(body.size - 3, 3).contentEquals(byteArrayOf(255.toByte(), 255.toByte(), 255.toByte()))
                ) { throw CameraError.Invalid("NCP 色彩设置超出支持范围") }
            }

            val curve = records[2]
            curve?.let { validateCurve(it) }

            val name = String(nameData.slice(0, end), Charsets.US_ASCII)
            return ClassicNcpRecipe(data.clone(), name, base, body.clone(), curve?.clone())
        }

        fun validateCurve(curve: ByteArray, allowLutOnly: Boolean = false) {
            val points = curve[8].u()
            require(
                curve.size == 578 && curve.slice(0, 2).contentEquals(byteArrayOf(0x49.toByte(), 0x30.toByte())) &&
                    curve[2].u() < curve[3].u() &&
                    curve[4].u() <= curve[5].u() &&
                    ((1..9).contains(curve[6].u()) || (curve[6].u() == 0 && curve[7].u() > 0))
            ) { throw CameraError.Invalid("NCP 曲线头或长度异常") }
            require(
                curve[7].u() < 100 &&
                    (allowLutOnly && points == 0 || (2..20).contains(points)) &&
                    (points < 2 || (1 until points).all {
                        curve[9 + (it - 1) * 2].u() < curve[9 + it * 2].u()
                    }) &&
                    curve.slice(9 + points * 2, 64 - (9 + points * 2)).all { it.toInt() == 0 } &&
                    (0 until 257).all {
                        val v = (curve[64 + it * 2].u() shl 8) or curve[65 + it * 2].u()
                        v <= 32767
                    }
            ) { throw CameraError.Invalid("NCP 曲线控制点或查找表异常") }
        }
    }

    fun wirePayload(): ByteArray {
        val bytes = ByteArray(36)
        bytes[0] = wireBaseIDs[base]!!.toByte()
        bytes.writeInto(2, settings.slice(4, 20))

        fun quarter(value: Int): Int {
            // Auto and N/A are sent as sentinels, not multiplied into zero.
            if (value == 0) return 128
            if (value == 255) return 127
            return ((value - 128) * 4) and 0xFF
        }

        bytes[25] = quarter(settings[28].u()).toByte()
        if (base == 0x064D) {
            bytes[30] = 127.toByte()
            bytes[31] = 127.toByte()
            bytes[32] = (settings[33].u() - 128).toByte()
            bytes[33] = (settings[34].u() - 128).toByte()
            bytes[34] = quarter(settings[35].u()).toByte()
        } else {
            bytes[30] = quarter(settings[31].u()).toByte()
            bytes[31] = quarter(settings[32].u()).toByte()
        }
        if (curve != null) {
            bytes[35] = 1.toByte()
            val result = ByteArray(36 + curve.size)
            System.arraycopy(bytes, 0, result, 0, bytes.size)
            System.arraycopy(curve, 0, result, bytes.size, curve.size)
            PictureControlWirePayload(result)
            return result
        }
        bytes[28] = quarter(settings[29].u()).toByte()
        bytes[29] = quarter(settings[30].u()).toByte()
        PictureControlWirePayload(bytes)
        return bytes
    }
}
