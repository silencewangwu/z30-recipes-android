package com.z30lab.core

/** Little-endian binary reader over a byte array, mirroring the iOS BinaryReader. */
class BinaryReader(val data: ByteArray) {
    var offset = 0
        private set

    fun bytes(count: Int): ByteArray {
        require(count >= 0 && count <= data.size - offset) { throw CameraError.Invalid("数据长度不足") }
        val result = data.copyOfRange(offset, offset + count)
        offset += count
        return result
    }

    fun u8(): Int = bytes(1)[0].toInt() and 0xFF

    fun u16(): Int {
        val b = bytes(2)
        return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
    }

    fun u32(): Long {
        val b = bytes(4)
        var v = 0L
        for (i in 0..3) v = v or ((b[i].toLong() and 0xFF) shl (i * 8))
        return v
    }

    fun u64(): Long {
        val low = u32()
        val high = u32()
        return low or (high shl 32)
    }

    fun string(): String {
        val length = u8()
        if (length <= 0) return ""
        val raw = bytes(length * 2)
        if (raw.size < 2 || (raw[raw.size - 2].toInt() and 0xFF) != 0 || (raw[raw.size - 1].toInt() and 0xFF) != 0) {
            throw CameraError.Invalid("PTP 字符串格式错误")
        }
        return decodeUtf16Le(raw, 0, raw.size - 2)
    }

    fun array16(): IntArray {
        val count = u32()
        require(count <= (data.size - offset) / 2L) { throw CameraError.Invalid("数组越界") }
        return IntArray(count.toInt()) { u16() }
    }
}

/** Decode UTF-16LE bytes (no BOM expected, but a leading BOM is skipped). */
fun decodeUtf16Le(bytes: ByteArray, start: Int, end: Int): String {
    val sb = StringBuilder((end - start) / 2)
    var i = start
    while (i + 1 <= end) {
        val c = (bytes[i].toInt() and 0xFF) or ((bytes[i + 1].toInt() and 0xFF) shl 8)
        if (sb.length == 0 && c == 0xFEFF) {
            i += 2
            continue
        }
        sb.append(c.toChar())
        i += 2
    }
    return sb.toString()
}
