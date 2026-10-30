package com.z30lab.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * PTP/IP camera session over a command/event socket pair. A mutex serializes
 * complete transactions and heartbeat traffic across suspension points
 * (mirrors the iOS FIFO lease gate).
 */
class PtpIpSession(
    private val heartbeatIntervalMs: Long = 10_000,
    private val scope: CoroutineScope
) {
    private val gate = Mutex()
    private var command: PacketSocket? = null
    private var event: PacketSocket? = null
    private var info: DeviceInfo? = null
    private var host: String? = null
    private var port: Int? = null
    private var generation = UUID.randomUUID()
    private var nextTransaction = 2L
    private var eventJob: Job? = null
    private var heartbeatJob: Job? = null
    private var outstandingProbeMs: Long = 0L
    private var log: suspend (String) -> Unit = {}
    private var state: suspend (Boolean) -> Unit = {}

    val connected: Boolean get() = command != null && event != null && info != null

    suspend fun connect(
        host: String,
        port: Int = 15740,
        guid: UUID,
        requireWifi: Boolean = true,
        log: suspend (String) -> Unit = {},
        state: suspend (Boolean) -> Unit = {}
    ) {
        gate.withLock {
            this.log = log
            this.state = state
            if (connected && this.host == host && this.port == port) {
                log("复用已建立的相机会话")
                state(true)
                return
            }
            if (command != null) closeCurrentSession()
            val commandSocket = PacketSocket(host, port, requireWifi, scope)
            val eventSocket = PacketSocket(host, port, requireWifi, scope)
            command = commandSocket
            event = eventSocket
            this.host = host
            this.port = port
            val id = UUID.randomUUID()
            generation = id
            try {
                log("连接相机 $host:$port")
                commandSocket.open()
                val payload = ByteArrayOutputStream()
                payload.write(uuidBytes(guid))
                payload.write("Z30 Recipes\u0000".toByteArray(Charsets.UTF_16LE))
                put32(payload, 0x00010000)
                commandSocket.write(PTPPacket(1L, payload.toByteArray()))
                val ack = commandSocket.read()
                if (ack.type == 5L) throw CameraError.Invalid("相机拒绝连接，请关闭 SnapBridge 后重试")
                require(ack.type == 2L && ack.payload.size >= 26) {
                    throw CameraError.Invalid("命令通道握手不完整")
                }
                val reader = BinaryReader(ack.payload)
                val eventPayload = ByteArrayOutputStream()
                put32(eventPayload, reader.u32())
                log("命令通道已连接")
                eventSocket.open()
                eventSocket.write(PTPPacket(3L, eventPayload.toByteArray()))
                val eventAck = eventSocket.read()
                require(eventAck.type == 4L && eventAck.payload.isEmpty()) {
                    throw CameraError.Invalid("事件通道握手失败")
                }
                log("事件通道已连接")
                eventJob = scope.launch {
                    try {
                        while (isActive) {
                            // Idle is normal. Active probes check liveness.
                            val packet = eventSocket.read(timeoutMs = null)
                            when (packet.type) {
                                13L -> eventSocket.write(PTPPacket(14L, ByteArray(0)))
                                14L -> receivedPong(id)
                                8L -> Unit
                                else -> throw CameraError.Invalid("事件通道收到意外数据")
                            }
                        }
                    } catch (e: Exception) {
                        if (isActive) lost(id, e)
                    }
                }
                operation(commandSocket, 0x1002, 0L, listOf(1L))
                log("相机会话已打开")
                val deviceInfo = DeviceInfo.parse(operation(commandSocket, 0x1001, 1L))
                require(
                    deviceInfo.manufacturer.contains("nikon", ignoreCase = true) &&
                        deviceInfo.model.replace(" ", "").uppercase() == "Z30"
                ) { throw CameraError.Invalid("当前相机是 ${deviceInfo.model}，本程序仅针对 Nikon Z30") }
                require(generation == id) { throw CameraError.Closed() }
                this.info = deviceInfo
                nextTransaction = 2
                log("识别到 ${deviceInfo.model}，固件 ${deviceInfo.firmware}")
                state(true)
                heartbeatJob = scope.launch {
                    try {
                        while (isActive) {
                            delay(heartbeatIntervalMs)
                            heartbeat(id)
                        }
                    } catch (e: Exception) {
                        if (!isActive) throw e
                        lost(id, e)
                    }
                }
            } catch (e: Exception) {
                lost(id, e)
                throw e
            }
        }
    }

    suspend fun read(backupSlots: Boolean = false): ProbeResult {
        gate.withLock {
            val command = command ?: throw CameraError.Closed()
            val info = info ?: throw CameraError.Closed()
            val id = generation
            try {
                val result = ProbeResult()
                result.model = info.model
                result.firmware = info.firmware
                result.operations = info.operations.map { "0x%04X".format(it) }
                var transaction = nextTransaction
                try {
                    val rawDesc = operation(command, 0x1014, transaction, listOf(0xD200L))
                    transaction += 1
                    val descriptor = PictureControlDescriptor.parse(rawDesc)
                    result.selectionReportedWritable = descriptor.writable
                    result.availableProfileCount = descriptor.choices.size
                } catch (e: CameraError.Response) {
                    transaction += 1
                    result.warnings.add("无线模式未读到优化校准属性表（0x%04X）".format(e.code))
                }
                try {
                    val rawValue = operation(command, 0x1015, transaction, listOf(0xD200L))
                    transaction += 1
                    val profile = BinaryReader(rawValue).u16()
                    result.profileID = profile
                    log("当前优化校准编号：$profile")
                } catch (e: CameraError.Response) {
                    transaction += 1
                    result.warnings.add("无线模式未读到当前优化校准（0x%04X）".format(e.code))
                }
                result.profileID?.let { profile ->
                    if (info.operations.contains(0x90CF)) {
                        try {
                            val capability = operation(command, 0x90CF, transaction, listOf(profile.toLong()))
                            transaction += 1
                            result.capabilityPayloadHex = capability.toHex()
                            log("已读取优化校准能力：${capability.size} 字节")
                        } catch (e: CameraError.Response) {
                            transaction += 1
                            result.warnings.add("能力查询返回 0x%04X".format(e.code))
                        }
                    }
                    if (info.operations.contains(0x90CC)) {
                        try {
                            val profileData = operation(command, 0x90CC, transaction, listOf(profile.toLong(), 0L))
                            transaction += 1
                            result.profilePayloadHex = profileData.toHex(4096)
                            if (profileData.size >= 22) {
                                val nameBytes = profileData.slice(2, 20).takeWhile { it.toInt() != 0 }.toByteArray()
                                if (nameBytes.isNotEmpty()) {
                                    result.profileName = String(nameBytes, Charsets.US_ASCII)
                                }
                            }
                            log("已读取优化校准数据：${profileData.size} 字节")
                        } catch (e: CameraError.Response) {
                            transaction += 1
                            result.warnings.add("优化校准读取返回 0x%04X".format(e.code))
                        }
                    }
                }
                if (backupSlots) {
                    require(info.operations.contains(0x90CC)) {
                        throw CameraError.Invalid("相机未声明支持优化校准读取")
                    }
                    for (slot in 1..9) {
                        val code = 200 + slot
                        val backup = SlotBackup(slot = slot, pictureControlID = code)
                        // Rejected reads do not establish whether a slot is empty.
                        try {
                            backup.payload = operation(command, 0x90CC, transaction, listOf(code.toLong(), 0L))
                            transaction += 1
                            backup.response = 0x2001
                        } catch (e: CameraError.Response) {
                            transaction += 1
                            backup.response = e.code
                        }
                        try {
                            backup.expandedPayload = operation(command, 0x90CC, transaction, listOf(code.toLong(), 1L))
                            transaction += 1
                            backup.expandedResponse = 0x2001
                        } catch (e: CameraError.Response) {
                            transaction += 1
                            backup.expandedResponse = e.code
                        }
                        result.slotBackups.add(backup)
                        if (backup.payload != null) {
                            log("C$slot：已读取 ${backup.payload!!.size} 字节，${backup.name ?: "未识别名称"}")
                        } else {
                            log("C$slot：读取返回 0x%04X，槽位状态待确认".format(backup.response ?: 0))
                        }
                    }
                }
                require(generation == id) { throw CameraError.Closed() }
                log("读取完成，连接保持开启")
                return result
            } catch (e: Exception) {
                lost(id, e)
                throw e
            }
        }
    }

    suspend fun disconnect() {
        gate.withLock {
            closeCurrentSession()
        }
    }

    suspend fun transferRecipes(items: List<RecipeTransfer>, activeSlot: Int): RecipeTransferResult {
        require(
            items.isNotEmpty() && items.size <= 9 &&
                items.map { it.slot }.toSet().size == items.size &&
                items.all { it.slot in 1..9 } &&
                items.any { it.slot == activeSlot }
        ) { throw CameraError.Invalid("请选择 1–9 个不同槽位，并从中选择要应用的配方") }
        // Validate the entire batch before touching any camera setting.
        val prepared = items.map { item ->
            val recipe = PhoneRecipeDocument.parse(item.np3)
            Triple(item.slot, recipe, recipe.verifiedPayload())
        }.toMutableList()
        gate.withLock {
            val command = command ?: throw CameraError.Closed()
            val info = info ?: throw CameraError.Closed()
            require(listOf(0x90CD, 0x90CC, 0x1016).all { info.operations.contains(it) }) {
                throw CameraError.Invalid("相机未声明支持所需的配方读写与选择指令")
            }
            val id = generation
            val descriptor = selectionDescriptor(command)
            require(descriptor.writable && prepared.all { descriptor.choices.contains(200 + it.first) }) {
                throw CameraError.Invalid("当前拍摄模式不允许载入和应用所选槽位，请切到 P／S／A／M 档")
            }
            // A destination may be empty or contain a different dataset. Read
            // built-in style data to retain the camera's own monochrome flags.
            val nativeStyles = HashMap<Int, ByteArray>()
            for (index in prepared.indices) {
                val base = prepared[index].third[0].toInt() and 0xFF
                if (base != 2 && !nativeStyles.containsKey(base)) {
                    try {
                        val native = recipeData(command, base, 0L)
                        if (native.size >= 36) nativeStyles[base] = native
                        log("机内基底 $base（${native.size} 字节）：${native.toHex(4096)}")
                    } catch (e: CameraError.Response) {
                        log("机内基底读取返回 0x%04X，继续发送文件参数".format(e.code))
                    }
                }
                val native = nativeStyles[base]
                if (native != null) {
                    val payload = prepared[index].third
                    payload[1] = native[1]
                    if (base == 4) {
                        for (offset in listOf(30, 31, 34)) {
                            if ((payload[offset].toInt() and 0xFF) == 127) payload[offset] = native[offset]
                        }
                    }
                }
            }
            val completed = mutableListOf<RecipeTransferredItem>()
            try {
                for (item in prepared) {
                    currentCoroutineContext().ensureActive()
                    val slot = item.first
                    val profile = 200 + slot
                    log("C$slot 发送 ${item.second.name}（${item.third.size} 字节）：${item.third.toHex(4096)}")
                    sendRecipeData(command, profile, item.third)
                    log("C$slot 相机已接受写入（0x2001），开始读回核对")
                    val basic = recipeData(command, profile, 0L)
                    val extended = recipeData(command, profile, 1L)
                    if (!basic.contentEquals(item.third)) {
                        log("C$slot 目标：${item.third.toHex(4096)}")
                        log("C$slot 读回 0：${basic.toHex(4096)}")
                        log("C$slot 读回 1：${extended.toHex(4096)}")
                    }
                    val verification = RecipeReadback.verify(item.third, basic, extended)
                    completed.add(
                        RecipeTransferredItem(
                            slot = slot,
                            name = item.second.name,
                            verifiedBytes = item.third.size - (if (verification.cameraFlagNormalized) 1 else 0),
                            cameraFlagNormalized = verification.cameraFlagNormalized,
                            currentStateMatchesStored = verification.currentStateMatchesStored
                        )
                    )
                    log(
                        "C$slot：${item.second.name}，保存的名称、参数与完整曲线核对通过" +
                            (if (verification.cameraFlagNormalized) "；相机返回了另一种已观测状态标记" else "")
                    )
                }
                val active = 200 + activeSlot
                setSelection(command, active)
                require(selectionValue(command) == active) {
                    throw CameraError.Verification("应用后的选择读回失败")
                }
                log("已应用 C$activeSlot，保持相机连接")
                return RecipeTransferResult(completed, activeSlot)
            } catch (e: Exception) {
                val confirmed = completed.joinToString("、") { "C${it.slot}" }
                log("同步未完成；已确认的槽位：${confirmed.ifEmpty { "无" }}。未自动回退，重新连接后可重试。")
                when (e) {
                    is CameraError.Response, is CameraError.Verification -> Unit
                    else -> lost(id, e)
                }
                throw CameraError.Invalid(
                    "同步未完成，部分槽位可能已更新。已读回确认：${confirmed.ifEmpty { "无" }}。${e.message}"
                )
            }
        }
    }

    suspend fun inspectSlot(slot: Int) {
        require(slot in 1..9) { throw CameraError.Invalid("槽位超出范围") }
        gate.withLock {
            val command = command ?: throw CameraError.Closed()
            val profile = 200 + slot
            val basic = recipeData(command, profile, 0L)
            val extended = recipeData(command, profile, 1L)
            log("C$slot 当前数据 0（${basic.size} 字节）：${basic.toHex(4096)}")
            log("C$slot 当前数据 1（${extended.size} 字节）：${extended.toHex(4096)}")
        }
    }

    // ---- C9 test / recovery (protocol test tooling preserved for Android diagnostics) ----

    suspend fun testC9Recipe(store: FileRecipeRecoveryStore): RecipeTestResult {
        gate.withLock {
            val command = command ?: throw CameraError.Closed()
            val info = info ?: throw CameraError.Closed()
            val identity = info.identityFingerprint ?: throw CameraError.Closed()
            require(info.operations.contains(0x90CD) && info.operations.contains(0x90CC)) {
                throw CameraError.Invalid("相机未声明支持配方数据读写")
            }
            require(store.load() == null) { throw CameraError.Invalid("请先恢复上次 C9 测试") }
            val id = generation
            val original = C9RecipePayload(c9Data(command, 0L))
            require(c9Data(command, 1L).contentEquals(original.bytes)) {
                throw CameraError.Invalid("C9 两种读取结果不同，未执行写入")
            }
            val selection = selectionValue(command)
            val descriptor = selectionDescriptor(command)
            require(descriptor.writable && descriptor.choices.contains(selection)) {
                throw CameraError.Invalid("请使用 P／S／A／M 拍摄模式后重试")
            }
            val temporary = original.renamedForTest()
            val record = RecipeRecovery(
                cameraIdentity = identity,
                original = original.bytes,
                temporary = temporary,
                originalSelection = selection
            )
            store.save(record)
            log("C9 原始数据与恢复记录已保存：${original.name}")
            try {
                writeC9Data(command, temporary)
                require(c9Data(command, 0L).contentEquals(temporary) && c9Data(command, 1L).contentEquals(temporary)) {
                    throw CameraError.Invalid("C9 写入后的数据读回不一致")
                }
                log("C9 临时名称已写入并读回确认")
                val restored = restoreC9Locked(record, store, id)
                return RecipeTestResult(
                    originalName = original.name,
                    temporaryName = C9RecipePayload(temporary).name,
                    restoredName = restored,
                    restoredBytesMatch = true,
                    selectionRestored = selection
                )
            } catch (e: Exception) {
                if (store.load() != null) {
                    try {
                        restoreC9Locked(record, store, id)
                    } catch (restoreError: Exception) {
                        log("C9 恢复尚未确认，请重新连接后点恢复 C9 测试配方")
                        lost(id, restoreError)
                    }
                }
                throw e
            }
        }
    }

    suspend fun restorePendingC9(store: FileRecipeRecoveryStore): String {
        gate.withLock {
            val record = store.load() ?: throw CameraError.Invalid("没有待恢复的 C9 测试")
            return restoreC9Locked(record, store, generation)
        }
    }

    suspend fun testSelection(store: SelectionRecoveryStore): SelectionTestResult {
        gate.withLock {
            val command = command ?: throw CameraError.Closed()
            val info = info ?: throw CameraError.Closed()
            val identity = info.identityFingerprint ?: throw CameraError.Invalid("需要连接相机并取得身份后再验证切换")
            require(info.operations.contains(0x1016)) {
                throw CameraError.Invalid("当前连接未声明支持设置写入")
            }
            require(store.load() == null) { throw CameraError.Invalid("有待恢复的设置，请先点恢复上次设置") }
            val id = generation
            val descriptor = selectionDescriptor(command)
            require(descriptor.writable) { throw CameraError.Invalid("当前拍摄模式不允许修改优化校准") }
            val original = selectionValue(command)
            require(descriptor.choices.contains(original)) { throw CameraError.Invalid("未找到适合验证的机内预设") }
            val target = listOf(1, 2).firstOrNull { it != original && descriptor.choices.contains(it) }
                ?: throw CameraError.Invalid("未找到适合验证的机内预设")
            val recovery = SelectionRecovery(cameraIdentity = identity, original = original, temporary = target)
            store.save(recovery)
            log("已保存恢复记录：原编号 $original，临时编号 $target")
            try {
                setSelection(command, target)
                val observed = selectionValue(command)
                require(observed == target) { throw CameraError.Invalid("切换后的读回值与目标不一致") }
                log("切换读回成功：$original → $observed")
                val restored = restoreLocked(recovery, store, id)
                return SelectionTestResult(original, target, observed, restored)
            } catch (e: Exception) {
                if (store.load() != null) {
                    try {
                        restoreLocked(recovery, store, id)
                    } catch (restoreError: Exception) {
                        log("尚未确认恢复，请重新连接后点恢复上次设置；原编号 $original")
                        lost(id, restoreError)
                    }
                }
                throw e
            }
        }
    }

    suspend fun restorePendingSelection(store: SelectionRecoveryStore): Int {
        gate.withLock {
            val recovery = store.load() ?: throw CameraError.Invalid("没有待恢复的设置")
            return restoreLocked(recovery, store, generation)
        }
    }

    // ---- Private helpers ----

    private suspend fun restoreC9Locked(record: RecipeRecovery, store: FileRecipeRecoveryStore, id: UUID): String {
        require(generation == id && command != null && info?.identityFingerprint == record.cameraIdentity) {
            throw CameraError.Invalid("C9 恢复记录与当前相机不匹配")
        }
        val command = command!!
        val original = C9RecipePayload(record.original)
        require(original.renamedForTest().contentEquals(record.temporary)) {
            throw CameraError.Invalid("C9 恢复记录内容不符")
        }
        val current = c9Data(command, 0L)
        require(current.contentEquals(record.original) || current.contentEquals(record.temporary)) {
            throw CameraError.Invalid("C9 出现测试之外的改动，请保留恢复记录并检查相机")
        }
        if (!current.contentEquals(record.original)) writeC9Data(command, record.original)
        require(c9Data(command, 0L).contentEquals(record.original) && c9Data(command, 1L).contentEquals(record.original)) {
            throw CameraError.Invalid("C9 原始数据恢复读回失败")
        }
        val selection = selectionValue(command)
        if (selection == 209 && selection != record.originalSelection) {
            val descriptor = selectionDescriptor(command)
            require(descriptor.writable && descriptor.choices.contains(record.originalSelection)) {
                throw CameraError.Invalid("C9 已恢复，但当前模式无法恢复原选择")
            }
            setSelection(command, record.originalSelection)
        }
        require(selectionValue(command) == record.originalSelection) {
            throw CameraError.Invalid("C9 已恢复，但优化校准选择发生了其他变化")
        }
        store.clear()
        log("C9 原名与全部 36 字节已恢复并读回确认；原选择保持不变")
        return original.name
    }

    private suspend fun restoreLocked(recovery: SelectionRecovery, store: SelectionRecoveryStore, id: UUID): Int {
        require(generation == id && command != null && info?.identityFingerprint == recovery.cameraIdentity) {
            throw CameraError.Invalid("恢复记录与当前相机不匹配，未执行恢复")
        }
        val command = command!!
        val descriptor = selectionDescriptor(command)
        require(descriptor.writable && descriptor.choices.contains(recovery.original)) {
            throw CameraError.Invalid("当前模式无法恢复原优化校准，请调整拍摄模式后重试")
        }
        val current = selectionValue(command)
        if (current != recovery.original) setSelection(command, recovery.original)
        val restored = selectionValue(command)
        require(restored == recovery.original) { throw CameraError.Invalid("原设置恢复读回失败") }
        store.clear()
        log("原设置已恢复并读回确认：$restored")
        return restored
    }

    private suspend fun c9Data(socket: PacketSocket, mode: Long): ByteArray = recipeData(socket, 209, mode)

    private suspend fun recipeData(socket: PacketSocket, profile: Int, mode: Long): ByteArray {
        require(profile in 1..8 || profile in 201..209) { throw CameraError.Invalid("配方槽位或读取模式不符") }
        require(mode <= 1) { throw CameraError.Invalid("配方槽位或读取模式不符") }
        val id = nextTransaction
        nextTransaction += 1
        return operation(socket, 0x90CC, id, listOf(profile.toLong(), mode))
    }

    private suspend fun writeC9Data(socket: PacketSocket, bytes: ByteArray) = writeRecipeData(socket, 209, bytes)

    private suspend fun writeRecipeData(socket: PacketSocket, profile: Int, bytes: ByteArray) {
        require(profile in 201..209) { throw CameraError.Invalid("只允许 C1–C9 自定义槽位") }
        C9RecipePayload(bytes)
        sendRecipeData(socket, profile, bytes)
    }

    private suspend fun sendRecipeData(socket: PacketSocket, profile: Int, bytes: ByteArray) {
        require(profile in 201..209 && (bytes.size == 36 || bytes.size == 614)) {
            throw CameraError.Invalid("配方写入槽位或长度超出已限定范围")
        }
        currentCoroutineContext().ensureActive()
        val id = nextTransaction
        nextTransaction += 1
        socket.write(PTPPacket.operation(0x90CD, id, listOf(profile.toLong(), 0L), dataPhase = 2L))
        val start = ByteArrayOutputStream()
        put32(start, id)
        put64(start, bytes.size.toLong())
        val end = ByteArrayOutputStream()
        put32(end, id)
        end.write(bytes)
        socket.write(PTPPacket(9L, start.toByteArray()))
        socket.write(PTPPacket(12L, end.toByteArray()))
        val response = socket.read()
        require(response.type == 7L) {
            socket.close()
            throw CameraError.Invalid("配方写入未收到有效响应")
        }
        PTPDataTransaction(id).consume(response)
    }

    private suspend fun selectionDescriptor(socket: PacketSocket): PictureControlDescriptor {
        val id = nextTransaction
        nextTransaction += 1
        return PictureControlDescriptor.parse(operation(socket, 0x1014, id, listOf(0xD200L)))
    }

    private suspend fun selectionValue(socket: PacketSocket): Int {
        val id = nextTransaction
        nextTransaction += 1
        val data = operation(socket, 0x1015, id, listOf(0xD200L))
        require(data.size == 2) { throw CameraError.Invalid("优化校准选择值长度不符") }
        return BinaryReader(data).u16()
    }

    private suspend fun setSelection(socket: PacketSocket, value: Int) {
        require(value in 1..8 || value in 101..120 || value in 201..209) {
            throw CameraError.Invalid("优化校准编号无效")
        }
        currentCoroutineContext().ensureActive()
        val id = nextTransaction
        nextTransaction += 1
        // The only allowed setting write is UInt16 to Nikon photo selection D200.
        socket.write(PTPPacket.operation(0x1016, id, listOf(0xD200L), dataPhase = 2L))
        val start = ByteArrayOutputStream()
        put32(start, id)
        put64(start, 2L)
        val end = ByteArrayOutputStream()
        put32(end, id)
        put16(end, value)
        socket.write(PTPPacket(9L, start.toByteArray()))
        socket.write(PTPPacket(12L, end.toByteArray()))
        val response = socket.read()
        require(response.type == 7L) {
            socket.close()
            throw CameraError.Invalid("设置写入未收到有效响应")
        }
        PTPDataTransaction(id).consume(response)
    }

    private suspend fun closeCurrentSession() {
        // Retire event/heartbeat ownership before intentional teardown.
        generation = UUID.randomUUID()
        heartbeatJob?.cancel()
        heartbeatJob = null
        val command = command
        val info = info
        if (command != null && info != null) {
            try {
                operation(command, 0x1003, nextTransaction)
            } catch (_: Exception) {
                // best-effort session close
            }
        }
        invalidate()
        state(false)
        log("已主动断开相机")
    }

    private fun invalidate() {
        generation = UUID.randomUUID()
        eventJob?.cancel()
        eventJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        command?.close()
        event?.close()
        command = null
        event = null
        info = null
        host = null
        port = null
        outstandingProbeMs = 0L
    }

    private suspend fun lost(id: UUID, error: Throwable) {
        if (generation != id) return
        invalidate()
        state(false)
        log("连接已中断：${error.message}")
    }

    private fun receivedPong(id: UUID) {
        if (generation == id) outstandingProbeMs = 0L
    }

    private suspend fun heartbeat(id: UUID) {
        gate.withLock {
            val command = command ?: return
            val event = event ?: return
            val info = info ?: return
            if (generation != id) return
            val now = System.currentTimeMillis()
            if (outstandingProbeMs != 0L) {
                if (now - outstandingProbeMs > 25_000) throw CameraError.Timeout()
            } else {
                outstandingProbeMs = now
                event.write(PTPPacket(13L, ByteArray(0)))
            }
            if (info.operations.contains(0x90C8)) {
                val transaction = nextTransaction
                nextTransaction += 1
                try {
                    operation(command, 0x90C8, transaction)
                } catch (e: CameraError.Response) {
                    if (e.code != 0x2019) throw e // camera busy still proves connectivity
                }
            }
        }
    }

    private suspend fun operation(
        socket: PacketSocket,
        code: Int,
        id: Long,
        parameters: List<Long> = emptyList()
    ): ByteArray {
        // Only session lifecycle, read queries, and Nikon readiness checks are allowed.
        require(ALLOWED_OPERATIONS.contains(code)) { throw CameraError.Invalid("此版本没有写入功能") }
        currentCoroutineContext().ensureActive()
        socket.write(PTPPacket.operation(code, id, parameters))
        val parser = PTPDataTransaction(id)
        repeat(4096) {
            val packet = socket.read(15_000)
            val data = parser.consume(packet)
            if (data != null) return data
        }
        socket.close()
        throw CameraError.Invalid("数据分段过多")
    }

    companion object {
        private val ALLOWED_OPERATIONS = setOf(0x1002, 0x1003, 0x1001, 0x1014, 0x1015, 0x90CF, 0x90CC, 0x90C8)

        fun uuidBytes(uuid: UUID): ByteArray {
            val bb = ByteBuffer.allocate(16)
            bb.order(ByteOrder.BIG_ENDIAN)
            bb.putLong(uuid.mostSignificantBits)
            bb.putLong(uuid.leastSignificantBits)
            return bb.array()
        }
    }
}

/** One-shot probe entry point, retained for diagnostics and protocol tests. */
object PtpIpProbe {
    suspend fun run(
        host: String,
        port: Int = 15740,
        guid: UUID,
        requireWifi: Boolean = true,
        backupSlots: Boolean = false,
        log: suspend (String) -> Unit = {},
        scope: CoroutineScope
    ): ProbeResult {
        val session = PtpIpSession(scope = scope)
        try {
            session.connect(host = host, port = port, guid = guid, requireWifi = requireWifi, log = log)
            val result = session.read(backupSlots = backupSlots)
            session.disconnect()
            return result
        } catch (e: Exception) {
            session.disconnect()
            throw e
        }
    }
}
