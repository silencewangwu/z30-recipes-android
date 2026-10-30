package com.z30lab.core

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File

// ---- Recovery records (JSON-persisted checkpoints written before any camera mutation) ----

data class SelectionRecovery(
    val cameraIdentity: String,
    val original: Int,
    val temporary: Int,
    val recordedAt: Long = System.currentTimeMillis()
)

interface SelectionRecoveryStore {
    fun load(): SelectionRecovery?
    fun save(recovery: SelectionRecovery)
    fun clear()
}

class FileSelectionRecoveryStore(private val file: File) : SelectionRecoveryStore {
    private val gson = Gson()

    override fun load(): SelectionRecovery? {
        if (!file.exists()) return null
        return runCatching { gson.fromJson(file.readText(), SelectionRecovery::class.java) }.getOrNull()
    }

    override fun save(recovery: SelectionRecovery) {
        file.parentFile?.mkdirs()
        file.writeText(gson.toJson(recovery))
        // Read back the checkpoint before permitting a write to the camera.
        require(load() == recovery) { throw CameraError.Invalid("恢复记录保存校验失败") }
    }

    override fun clear() {
        if (file.exists()) file.delete()
    }
}

data class RecipeRecovery(
    val cameraIdentity: String,
    val original: ByteArray,
    val temporary: ByteArray,
    val originalSelection: Int
)

class FileRecipeRecoveryStore(private val file: File) {
    private val gson: Gson = GsonBuilder().create()

    fun load(): RecipeRecovery? {
        if (!file.exists()) return null
        return runCatching { gson.fromJson(file.readText(), RecipeRecovery::class.java) }.getOrNull()
    }

    fun save(record: RecipeRecovery) {
        file.parentFile?.mkdirs()
        file.writeText(gson.toJson(record))
        val reloaded = load()
        require(reloaded != null && reloaded.cameraIdentity == record.cameraIdentity &&
            reloaded.original.contentEquals(record.original) &&
            reloaded.temporary.contentEquals(record.temporary) &&
            reloaded.originalSelection == record.originalSelection) {
            throw CameraError.Invalid("C9 恢复记录保存校验失败")
        }
    }

    fun clear() {
        if (file.exists()) file.delete()
    }
}

// ---- Probe / test / transfer result records ----

data class SlotBackup(
    val slot: Int,
    val pictureControlID: Int,
    var payload: ByteArray? = null,
    var expandedPayload: ByteArray? = null,
    var response: Int? = null,
    var expandedResponse: Int? = null
) {
    val name: String?
        get() {
            val payload = payload ?: return null
            if (payload.size < 22) return null
            val nameBytes = payload.slice(2, 20).takeWhile { it.toInt() != 0 }.toByteArray()
            return if (nameBytes.isEmpty()) null else String(nameBytes, Charsets.US_ASCII)
        }
}

class ProbeResult {
    var model: String = ""
    var firmware: String = ""
    var operations: List<String> = emptyList()
    var profileID: Int? = null
    var profileName: String? = null
    var selectionReportedWritable: Boolean? = null
    var availableProfileCount: Int? = null
    var profilePayloadHex: String? = null
    var capabilityPayloadHex: String? = null
    var warnings: MutableList<String> = mutableListOf()
    var transport: String = "Wi-Fi PTP/IP"
    var writeTested: Boolean = false
    var testedAt: Long = System.currentTimeMillis()
    var slotBackups: MutableList<SlotBackup> = mutableListOf()

    val supportsRecipeWrite: Boolean get() = operations.contains("0x90CD")

    val json: String get() = GsonBuilder().setPrettyPrinting().create().toJson(this)
}

data class SelectionTestResult(
    val original: Int,
    val temporary: Int,
    val observedTemporary: Int,
    val restored: Int,
    var recipeWriteTested: Boolean = false,
    var testedAt: Long = System.currentTimeMillis()
)

data class RecipeTestResult(
    val originalName: String,
    val temporaryName: String,
    val restoredName: String,
    val restoredBytesMatch: Boolean,
    val selectionRestored: Int,
    var testedAt: Long = System.currentTimeMillis()
)

data class RecipeTransfer(val slot: Int, val np3: ByteArray)

data class RecipeTransferredItem(
    val slot: Int,
    val name: String,
    val verifiedBytes: Int,
    var cameraFlagNormalized: Boolean = false,
    var currentStateMatchesStored: Boolean = true
)

class RecipeReadback(val cameraFlagNormalized: Boolean, val currentStateMatchesStored: Boolean) {
    companion object {
        fun verify(expected: ByteArray, basic: ByteArray, extended: ByteArray): RecipeReadback {
            PictureControlWirePayload(expected)
            require(
                basic.size == expected.size &&
                    (basic[23].toInt() and 0xFF) <= 1 &&
                    expected.indices.all { it == 23 || basic[it] == expected[it] }
            ) { throw CameraError.Verification("保存的名称、基础风格、参数或完整曲线与目标不一致") }
            val cameraFlagNormalized = basic[23] != expected[23]
            val currentStateMatchesStored = extended.contentEquals(basic)
            return RecipeReadback(cameraFlagNormalized, currentStateMatchesStored)
        }
    }
}

data class RecipeTransferResult(
    val items: List<RecipeTransferredItem>,
    val activeSlot: Int,
    var testedAt: Long = System.currentTimeMillis()
)
