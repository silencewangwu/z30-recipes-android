package com.z30lab.core

/** Byte-array helpers shared across the codec. */
fun ByteArray.slice(offset: Int, length: Int): ByteArray = copyOfRange(offset, offset + length)

fun ByteArray.writeInto(offset: Int, src: ByteArray) {
    System.arraycopy(src, 0, this, offset, src.size)
}

fun ByteArray.indexOfByte(value: Int, from: Int = 0): Int {
    for (i in from until size) if ((this[i].toInt() and 0xFF) == value) return i
    return -1
}

fun ByteArray.toHex(limit: Int = 48): String {
    val end = minOf(limit, size)
    val sb = StringBuilder()
    for (i in 0 until end) {
        if (i > 0) sb.append(' ')
        sb.append("%02X".format(this[i].toInt() and 0xFF))
    }
    return sb.toString()
}

/** Wrapper so byte arrays can be dictionary keys with content equality. */
class ByteArrayKey(val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean = other is ByteArrayKey && bytes.contentEquals(other.bytes)
    override fun hashCode(): Int = bytes.contentHashCode()
}

/** Big-endian helpers used while encoding PTP/IP packets. */
fun put16(out: java.io.ByteArrayOutputStream, value: Int) {
    out.write(value and 0xFF)
    out.write((value shr 8) and 0xFF)
}

fun put32(out: java.io.ByteArrayOutputStream, value: Long) {
    for (shift in 0 until 32 step 8) out.write(((value shr shift) and 0xFF).toInt())
}

fun put64(out: java.io.ByteArrayOutputStream, value: Long) {
    for (shift in 0 until 64 step 8) out.write(((value shr shift) and 0xFF).toInt())
}

/** NCP/NP3 container parser: "NCP\0" magic followed by big-endian tag/length records. */
class Np3Document(val records: Map<Long, ByteArray>) {
    companion object {
        private val MAGIC = byteArrayOf(78.toByte(), 67.toByte(), 80.toByte(), 0.toByte())

        fun parse(data: ByteArray): Np3Document {
            require(data.size <= 65536) { throw CameraError.Invalid("配方文件过大") }
            val reader = BinaryReader(data)
            require(reader.bytes(4).contentEquals(MAGIC)) { throw CameraError.Invalid("不是 Nikon Picture Control 文件") }
            val records = LinkedHashMap<Long, ByteArray>()
            while (true) {
                val tag = big32(reader.bytes(4))
                if (tag == 0L) {
                    require(reader.offset == data.size) { throw CameraError.Invalid("配方结束标记后有额外数据") }
                    break
                }
                require(records.size < 64 && !records.containsKey(tag)) { throw CameraError.Invalid("配方记录重复或过多") }
                val count = big32(reader.bytes(4)).toInt()
                records[tag] = reader.bytes(count)
            }
            return Np3Document(records)
        }

        private fun big32(data: ByteArray): Long {
            var v = 0L
            for (b in data) v = (v shl 8) or (b.toLong() and 0xFF)
            return v
        }
    }
}
