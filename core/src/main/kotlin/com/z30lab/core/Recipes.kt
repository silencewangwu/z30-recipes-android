package com.z30lab.core

/** Wrapper validating the 36-byte (or 36+578 curve) wire dataset framing. */
class PictureControlWirePayload(val bytes: ByteArray) {
    init {
        require(bytes.size == 36 || bytes.size == 614) { throw CameraError.Invalid("配方数据长度或曲线标记不完整") }
        require((bytes[35].toInt() and 0xFF) == if (bytes.size == 614) 1 else 0) {
            throw CameraError.Invalid("配方数据长度或曲线标记不完整")
        }
        val name = bytes.slice(2, 20)
        val end = name.indexOfByte(0)
        require(
            end > 0 &&
                name.slice(0, end).all { (it.toInt() and 0xFF) in 32..126 } &&
                name.slice(end, name.size - end).all { it.toInt() == 0 }
        ) { throw CameraError.Invalid("优化校准名称无效") }
        if (bytes.size == 614) ClassicNcpRecipe.validateCurve(bytes.slice(36, 578), allowLutOnly = true)
    }
}

/** Limited to basic STANDARD datasets without curves or automatic parameters. */
class C9RecipePayload(val bytes: ByteArray) {
    val name: String = run {
        val nameBytes = bytes.slice(2, 20)
        val zero = nameBytes.indexOfByte(0)
        if (zero <= 0) "" else String(nameBytes.slice(0, zero), Charsets.US_ASCII)
    }

    init {
        require(bytes.size == 36) { throw CameraError.Invalid("C9 不是已验证格式的基本标准配方，未执行写入") }
        require(
            bytes.slice(0, 2).contentEquals(byteArrayOf(1.toByte(), 0.toByte())) &&
                bytes[22].u() == 0 && bytes[23].u() <= 1 && bytes[24].u() == 0 &&
                bytes.slice(bytes.size - 4, 4).all { it.u() == 0 } &&
                StandardNp3Recipe.parameters.all { p -> p.quarters.contains(bytes[p.offset].toInt()) }
        ) { throw CameraError.Invalid("C9 不是已验证格式的基本标准配方，未执行写入") }
        val nameBytes = bytes.slice(2, 20)
        val zero = nameBytes.indexOfByte(0)
        require(
            zero > 0 &&
                nameBytes.slice(0, zero).all { (it.toInt() and 0xFF) in 32..126 } &&
                nameBytes.slice(zero, nameBytes.size - zero).all { it.toInt() == 0 }
        ) { throw CameraError.Invalid("C9 测试配方名称格式不符") }
    }

    fun renamedForTest(): ByteArray {
        val newName = if (name == "Z30LAB-TEST") "Z30LAB-CHECK" else "Z30LAB-TEST"
        val result = bytes.clone()
        val field = ByteArray(20)
        val nameBytes = newName.toByteArray(Charsets.US_ASCII)
        System.arraycopy(nameBytes, 0, field, 0, nameBytes.size)
        result.writeInto(2, field)
        return result
    }
}

/** Standard 0300 NP3 (non-neutral bases). Tags and offsets paired against a camera-exported Z30 0300 file. */
class StandardNp3Recipe(val document: Np3Document, val name: String) {
    val summary: String = parameters.joinToString(" · ") { p ->
        "${p.label} ${((document.records[p.tag]!![0].toInt() and 0xFF) - 128) / 4.0}"
    }

    val payload: ByteArray = run {
        val data = ByteArray(36)
        data[0] = 1.toByte()
        data.writeInto(2, document.records[0x200L]!!)
        for (p in parameters) {
            val record = document.records[p.tag]!!
            data[p.offset] = (((record[0].toInt() and 0xFF) - 128) and 0xFF).toByte()
        }
        data
    }

    companion object {
        data class Parameter(val tag: Long, val offset: Int, val label: String, val quarters: IntRange)

        val parameters: List<Parameter> = listOf(
            Parameter(0x600L, 25, "锐化", -12..36),
            Parameter(0x1600L, 26, "中等范围锐化", -20..20),
            Parameter(0x700L, 27, "清晰度", -20..20),
            Parameter(0x800L, 28, "对比度", -12..12),
            Parameter(0x900L, 29, "亮度", -6..6),
            Parameter(0xA00L, 30, "饱和度", -12..12),
            Parameter(0xB00L, 31, "色相", -12..12)
        )

        private val REQUIRED_TAGS: Set<Long> = (1..22).map { it.toLong() * 256 }.toSet()
        private val VERSION_0300 = "0300".toByteArray(Charsets.US_ASCII)

        fun parse(data: ByteArray): StandardNp3Recipe {
            val document = Np3Document.parse(data)
            val records = document.records
            require(
                records.keys == REQUIRED_TAGS &&
                    records[0x100L]?.contentEquals(VERSION_0300) == true &&
                    records[0x300L]?.contentEquals(byteArrayOf(0.toByte(), 1.toByte())) == true
            ) { throw CameraError.Invalid("这份 NP3 的版本、基础风格或扩展设置暂不支持") }
            val constants = mapOf<Long, ByteArray>(
                0x400L to byteArrayOf(0.toByte(), 0.toByte()),
                0x500L to byteArrayOf(255.toByte(), 1.toByte()),
                0xC00L to byteArrayOf(255.toByte(), 0.toByte()),
                0xD00L to byteArrayOf(255.toByte(), 0.toByte()),
                0xE00L to byteArrayOf(255.toByte(), 4.toByte()),
                0xF00L to byteArrayOf(255.toByte(), 1.toByte()),
                0x1000L to byteArrayOf(255.toByte(), 1.toByte()),
                0x1100L to byteArrayOf(255.toByte(), 1.toByte()),
                0x1200L to byteArrayOf(255.toByte(), 1.toByte()),
                0x1300L to byteArrayOf(255.toByte(), 1.toByte()),
                0x1400L to byteArrayOf(128.toByte(), 1.toByte()),
                0x1500L to byteArrayOf(255.toByte(), 10.toByte())
            )
            require(constants.all { (tag, value) -> records[tag]?.contentEquals(value) == true }) {
                throw CameraError.Invalid("NP3 含尚未验证的扩展设置，未允许载入")
            }
            val nameData = records[0x200L] ?: throw CameraError.Invalid("NP3 缺少名称数据")
            require(nameData.size == 20) { throw CameraError.Invalid("NP3 名称须为 1–19 个 ASCII 字符") }
            val end = nameData.indexOfByte(0)
            require(
                end > 0 &&
                    nameData.slice(0, end).all { (it.toInt() and 0xFF) in 32..126 } &&
                    nameData.slice(end, nameData.size - end).all { it.toInt() == 0 }
            ) { throw CameraError.Invalid("NP3 名称须为 1–19 个 ASCII 字符") }
            for (p in parameters) {
                val record = records[p.tag] ?: throw CameraError.Invalid("${p.label} 参数缺失")
                require(
                    record.size == 2 && (record[1].toInt() and 0xFF) == 4 &&
                        p.quarters.contains((record[0].toInt() and 0xFF) - 128)
                ) { throw CameraError.Invalid("${p.label} 含不支持的自动、曲线或超范围数值") }
            }
            val name = String(nameData.slice(0, end), Charsets.US_ASCII)
            return StandardNp3Recipe(document, name)
        }
    }
}

/** Neutral base NP3 with full manual parameter set and optional custom curve. */
class NeutralNp3Recipe(val name: String, val payload: ByteArray, val hasCurve: Boolean) {
    val summary: String = "自然 · " + (if (hasCurve) "自定义曲线" else "手动参数")

    companion object {
        fun parse(data: ByteArray): NeutralNp3Recipe {
            val records = Np3Document.parse(data).records
            val hasCurve = records.containsKey(2L)
            val required = (1..22).map { it.toLong() * 256 }.toSet() + if (hasCurve) setOf(2L) else emptySet()
            require(
                records.keys == required &&
                    records[0x100L]?.contentEquals("0300".toByteArray(Charsets.US_ASCII)) == true &&
                    records[0x300L]?.contentEquals(byteArrayOf(3.toByte(), 194.toByte())) == true &&
                    (records[0x400L]?.contentEquals(byteArrayOf(0.toByte(), 0.toByte())) == true ||
                        records[0x400L]?.contentEquals(byteArrayOf(2.toByte(), 0.toByte())) == true)
            ) { throw CameraError.Invalid("此自然基底 NP3 版本或扩展设置暂不支持") }
            val constants = mapOf<Long, ByteArray>(
                0x500L to byteArrayOf(255.toByte(), 1.toByte()),
                0xC00L to byteArrayOf(255.toByte(), 0.toByte()),
                0xD00L to byteArrayOf(255.toByte(), 0.toByte()),
                0xE00L to byteArrayOf(255.toByte(), 4.toByte()),
                0xF00L to byteArrayOf(255.toByte(), 1.toByte()),
                0x1000L to byteArrayOf(255.toByte(), 1.toByte()),
                0x1100L to byteArrayOf(255.toByte(), 1.toByte()),
                0x1200L to byteArrayOf(255.toByte(), 1.toByte()),
                0x1300L to byteArrayOf(255.toByte(), 1.toByte()),
                0x1400L to byteArrayOf(128.toByte(), 1.toByte()),
                0x1500L to byteArrayOf(255.toByte(), 10.toByte())
            )
            require(constants.all { (tag, value) -> records[tag]?.contentEquals(value) == true }) {
                throw CameraError.Invalid("NP3 含未支持的扩展设置")
            }
            val nameData = records[0x200L] ?: throw CameraError.Invalid("NP3 缺少名称数据")
            require(nameData.size == 20) { throw CameraError.Invalid("NP3 名称数据长度不符") }

            val bytes = ByteArray(36)
            bytes[0] = 2.toByte()
            bytes.writeInto(2, nameData)
            for (p in StandardNp3Recipe.parameters) {
                val value = records[p.tag] ?: throw CameraError.Invalid("NP3 缺少${p.label}参数")
                require(value.size == 2 && (value[1].toInt() and 0xFF) == 4) { throw CameraError.Invalid("NP3 参数格式不符") }
                if (hasCurve && (p.tag == 0x800L || p.tag == 0x900L)) {
                    require((value[0].toInt() and 0xFF) == 1) { throw CameraError.Invalid("NP3 曲线标记不一致") }
                } else {
                    require(p.quarters.contains((value[0].toInt() and 0xFF) - 128)) {
                        throw CameraError.Invalid("NP3 自动参数暂不支持")
                    }
                    bytes[p.offset] = (((value[0].toInt() and 0xFF) - 128) and 0xFF).toByte()
                }
            }
            val curve = records[2L]
            if (curve != null) {
                bytes[35] = 1.toByte()
                val result = ByteArray(36 + curve.size)
                System.arraycopy(bytes, 0, result, 0, bytes.size)
                System.arraycopy(curve, 0, result, bytes.size, curve.size)
                NeutralPictureControlPayload(result)
                val end = nameData.indexOfByte(0)
                val name = if (end > 0) String(nameData.slice(0, end), Charsets.US_ASCII) else ""
                return NeutralNp3Recipe(name, result, true)
            }
            NeutralPictureControlPayload(bytes)
            val end = nameData.indexOfByte(0)
            val name = if (end > 0) String(nameData.slice(0, end), Charsets.US_ASCII) else ""
            return NeutralNp3Recipe(name, bytes, false)
        }
    }
}

/** Natural optimization calibration payload validation (36 or 614 bytes). */
class NeutralPictureControlPayload(val bytes: ByteArray) {
    init {
        require(bytes.size == 36 || bytes.size == 614) { throw CameraError.Invalid("自然优化校准的数据格式或参数范围不符") }
        require(bytes.slice(0, 2).contentEquals(byteArrayOf(2.toByte(), 0.toByte()))) {
            throw CameraError.Invalid("自然优化校准的数据格式或参数范围不符")
        }
        require(
            bytes[22].u() == 0 && bytes[23].u() <= 1 && bytes[24].u() == 0 &&
                bytes.slice(32, 3).all { it.u() == 0 } &&
                (bytes[35].u()) == (if (bytes.size == 614) 1 else 0) &&
                StandardNp3Recipe.parameters.all { p -> p.quarters.contains(bytes[p.offset].toInt()) }
        ) { throw CameraError.Invalid("自然优化校准的数据格式或参数范围不符") }
        require(bytes.size != 614 || (bytes[28].u() == 0 && bytes[29].u() == 0)) {
            throw CameraError.Invalid("自然优化校准的数据格式或参数范围不符")
        }
        val name = bytes.slice(2, 20)
        val end = name.indexOfByte(0)
        require(
            end > 0 &&
                name.slice(0, end).all { (it.toInt() and 0xFF) in 32..126 } &&
                name.slice(end, name.size - end).all { it.toInt() == 0 }
        ) { throw CameraError.Invalid("优化校准名称无效") }
        if (bytes.size == 614) ClassicNcpRecipe.validateCurve(bytes.slice(36, 578), allowLutOnly = true)
    }
}

/** Ordinary 0300/0310 NP3 whose base maps to a known wire dataset. */
class OrdinaryNp3Recipe(val name: String, val summary: String, val payload: ByteArray) {
    companion object {
        fun parse(data: ByteArray): OrdinaryNp3Recipe {
            val records = Np3Document.parse(data).records
            val version = records[0x100L] ?: throw CameraError.Invalid("NP3 缺少版本、基础风格或名称数据")
            require(
                version.contentEquals("0300".toByteArray(Charsets.US_ASCII)) ||
                    version.contentEquals("0310".toByteArray(Charsets.US_ASCII))
            ) { throw CameraError.Invalid("NP3 缺少版本、基础风格或名称数据") }
            val baseData = records[0x300L] ?: throw CameraError.Invalid("NP3 缺少版本、基础风格或名称数据")
            require(baseData.size == 2) { throw CameraError.Invalid("NP3 缺少版本、基础风格或名称数据") }
            val nameData = records[0x200L] ?: throw CameraError.Invalid("NP3 缺少版本、基础风格或名称数据")
            require(nameData.size == 20) { throw CameraError.Invalid("NP3 缺少版本、基础风格或名称数据") }
            val base = ((baseData[0].toInt() and 0xFF) shl 8) or (baseData[1].toInt() and 0xFF)
            val wireBase = ClassicNcpRecipe.wireBaseIDs[base]
                ?: throw CameraError.Invalid("此 NP3 基础风格尚无对应的无线数据编码")

            val bytes = ByteArray(36)
            bytes[0] = wireBase.toByte()
            bytes.writeInto(2, nameData)
            val curve = records[2L]
            for (p in StandardNp3Recipe.parameters) {
                val value = records[p.tag] ?: throw CameraError.Invalid("NP3 缺少${p.label}参数")
                require(value.size == 2) { throw CameraError.Invalid("NP3 缺少${p.label}参数") }
                if (curve != null && (p.tag == 0x800L || p.tag == 0x900L)) continue
                bytes[p.offset] = (((value[0].toInt() and 0xFF) - 128) and 0xFF).toByte()
            }
            if (wireBase == 4) {
                for ((tag, offset) in listOf(0xC00L to 32, 0xD00L to 33, 0xE00L to 34)) {
                    val value = records[tag] ?: throw CameraError.Invalid("NP3 单色参数不完整")
                    require(value.size == 2) { throw CameraError.Invalid("NP3 单色参数不完整") }
                    bytes[offset] = (((value[0].toInt() and 0xFF) - 128) and 0xFF).toByte()
                }
            }
            val result: ByteArray = if (curve != null) {
                bytes[35] = 1.toByte()
                val combined = ByteArray(36 + curve.size)
                System.arraycopy(bytes, 0, combined, 0, bytes.size)
                System.arraycopy(curve, 0, combined, bytes.size, curve.size)
                PictureControlWirePayload(combined)
                combined
            } else {
                PictureControlWirePayload(bytes)
                bytes
            }
            val end = nameData.indexOfByte(0)
            val name = if (end > 0) String(nameData.slice(0, end), Charsets.US_ASCII) else ""
            val baseName = mapOf(1 to "标准", 2 to "自然", 3 to "鲜艳", 4 to "单色", 5 to "人像", 6 to "风景")[wireBase] ?: "未知"
            val summary = "$baseName · " + (if (curve == null) "参数配方" else "自定义曲线")
            return OrdinaryNp3Recipe(name, summary, result)
        }
    }
}

/** Discriminated phone-recipe document: np3 / neutralNP3 / ncp / ordinaryNP3. */
sealed class PhoneRecipeDocument {
    abstract val name: String
    abstract val summary: String
    abstract val fileExtension: String
    abstract val transferIssue: String?
    abstract fun verifiedPayload(): ByteArray

    class Np3(val recipe: StandardNp3Recipe) : PhoneRecipeDocument() {
        override val name get() = recipe.name
        override val summary get() = recipe.summary
        override val fileExtension get() = "NP3"
        override val transferIssue: String? = null
        override fun verifiedPayload() = recipe.payload
    }

    class NeutralNp3(val recipe: NeutralNp3Recipe) : PhoneRecipeDocument() {
        override val name get() = recipe.name
        override val summary get() = recipe.summary
        override val fileExtension get() = "NP3"
        override val transferIssue: String? = null
        override fun verifiedPayload() = recipe.payload
    }

    class Ncp(val recipe: ClassicNcpRecipe) : PhoneRecipeDocument() {
        override val name get() = recipe.name
        override val summary get() = recipe.summary
        override val fileExtension get() = "NCP"
        override val transferIssue: String? = null
        override fun verifiedPayload() = recipe.wirePayload()
    }

    class OrdinaryNp3(val recipe: OrdinaryNp3Recipe) : PhoneRecipeDocument() {
        override val name get() = recipe.name
        override val summary get() = recipe.summary
        override val fileExtension get() = "NP3"
        override val transferIssue: String? = null
        override fun verifiedPayload() = recipe.payload
    }

    companion object {
        fun parse(data: ByteArray): PhoneRecipeDocument {
            val records = Np3Document.parse(data).records
            return when {
                records.containsKey(1L) -> Ncp(ClassicNcpRecipe.parse(data))
                records[0x100L]?.contentEquals("0310".toByteArray(Charsets.US_ASCII)) == true &&
                    records[0x300L]?.contentEquals(byteArrayOf(0.toByte(), 32.toByte())) == true ->
                    throw CameraError.Invalid("这是灵活色彩云创配方，Z30 无法直接载入。请选择兼容的 NCP 或普通优化校准 NP3。")
                else -> OrdinaryNp3(OrdinaryNp3Recipe.parse(data))
            }
        }
    }
}
