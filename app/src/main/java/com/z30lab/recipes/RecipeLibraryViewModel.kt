package com.z30lab.recipes

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.z30lab.core.CameraError
import com.z30lab.core.FileRecipeRecoveryStore
import com.z30lab.core.FileSelectionRecoveryStore
import com.z30lab.core.PhoneRecipe
import com.z30lab.core.PtpIpSession
import com.z30lab.core.RecipeLibraryImporter
import com.z30lab.core.RecipeTransfer
import com.z30lab.core.RecipeTransferResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Port of the iOS RecipeLibraryModel: owns the camera session, the on-device
 * recipe library, transfer orchestration and recovery bookkeeping.
 */
class RecipeLibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val _recipes = MutableStateFlow<List<PhoneRecipe>>(emptyList())
    val recipes: StateFlow<List<PhoneRecipe>> = _recipes

    private val _selectedID = MutableStateFlow<String?>(null)
    val selectedID: StateFlow<String?> = _selectedID

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private val _critical = MutableStateFlow(false)
    val critical: StateFlow<Boolean> = _critical

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _report = MutableStateFlow<RecipeTransferResult?>(null)
    val report: StateFlow<RecipeTransferResult?> = _report

    private val _currentProfile = MutableStateFlow<String?>(null)
    val currentProfile: StateFlow<String?> = _currentProfile

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs

    private val _legacyRecovery = MutableStateFlow(false)
    val legacyRecovery: StateFlow<Boolean> = _legacyRecovery

    private val _importNotice = MutableStateFlow<String?>(null)
    val importNotice: StateFlow<String?> = _importNotice

    private val prefs = application.getSharedPreferences("z30lab", Context.MODE_PRIVATE)
    private val session = PtpIpSession(scope = viewModelScope)

    private val recipesFolder: File get() = File(getApplication<Application>().filesDir, "Recipes")
    private val recoveryFolder: File get() = File(getApplication<Application>().filesDir, "Recovery")
    private val selectionStore: FileSelectionRecoveryStore
        get() = FileSelectionRecoveryStore(File(recoveryFolder, "selection.json"))
    private val recipeStore: FileRecipeRecoveryStore
        get() = FileRecipeRecoveryStore(File(recoveryFolder, "c9.json"))

    val selected: PhoneRecipe? get() = _recipes.value.firstOrNull { it.id == _selectedID.value }

    init {
        reloadLibrary()
        refreshRecovery()
    }

    fun reloadLibrary() {
        try {
            _recipes.value = RecipeLibraryImporter.listRecipes(recipesFolder)
            val selected = _selectedID.value
            if (selected == null || _recipes.value.none { it.id == selected }) {
                _selectedID.value = _recipes.value.firstOrNull()?.id
            }
        } catch (e: Exception) {
            _error.value = "手机配方库读取失败：${e.message}"
        }
    }

    private fun refreshRecovery() {
        try {
            _legacyRecovery.value = selectionStore.load() != null || recipeStore.load() != null
        } catch (e: Exception) {
            _legacyRecovery.value = true
            _error.value = "上次测试恢复记录无法读取：${e.message}"
        }
    }

    fun selectRecipe(recipe: PhoneRecipe) {
        _selectedID.value = recipe.id
        _report.value = null
        _error.value = null
        _importNotice.value = null
    }

    fun dismissError() {
        _error.value = null
    }

    fun importFiles(uris: List<Uri>) {
        if (_running.value || uris.isEmpty()) return
        viewModelScope.launch {
            try {
                val files = uris.mapNotNull { copyUriToTemp(it) }
                val result = RecipeLibraryImporter.importFiles(files, recipesFolder)
                reloadLibrary()
                val first = result.importedIDs.firstOrNull() ?: result.duplicateIDs.firstOrNull()
                if (first != null) _selectedID.value = first
                _importNotice.value = result.summary
                _report.value = null
                _error.value = if (result.failures.isEmpty()) null else result.failures.joinToString("\n")
                files.forEach { it.delete() }
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }

    private fun copyUriToTemp(uri: Uri): File? {
        val resolver = getApplication<Application>().contentResolver
        val name = queryDisplayName(uri) ?: "recipe"
        val tmp = File(getApplication<Application>().cacheDir, "import_${System.currentTimeMillis()}_$name")
        return try {
            resolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            if (tmp.length() > 65536) {
                tmp.delete()
                null
            } else {
                tmp
            }
        } catch (e: Exception) {
            tmp.delete()
            null
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            getApplication<Application>().contentResolver
                .query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        } catch (e: Exception) {
            null
        }
    }

    fun deleteRecipe(recipe: PhoneRecipe) {
        if (_running.value) return
        viewModelScope.launch {
            try {
                recipe.file.delete()
                reloadLibrary()
                _error.value = null
                _report.value = null
            } catch (e: Exception) {
                _error.value = "手机配方删除失败：${e.message}"
            }
        }
    }

    fun transfer(host: String, assignments: Map<Int, String>, activeSlot: Int) {
        if (_running.value) return
        viewModelScope.launch {
            try {
                val items = assignments.toSortedMap().map { (slot, id) ->
                    val recipe = _recipes.value.firstOrNull { it.id == id }
                        ?: throw CameraError.Invalid("所选手机配方已不存在")
                    val data = recipe.file.readBytes()
                    RecipeTransfer(slot = slot, np3 = data)
                }
                run(host, critical = true) {
                    if (selectionStore.load() != null || recipeStore.load() != null) {
                        throw CameraError.Invalid("有未完成的旧操作，请先在连接设置中恢复")
                    }
                    val report = session.transferRecipes(items, activeSlot)
                    _report.value = report
                    _currentProfile.value = "C${report.activeSlot} · " +
                        (report.items.firstOrNull { it.slot == report.activeSlot }?.name ?: "")
                    try {
                        val json = report.toString()
                        File(getApplication<Application>().filesDir, "LatestTransfer.json")
                            .writeText(json)
                    } catch (e: Exception) {
                        append("相机载入已确认，手机报告保存失败：${e.message}")
                    }
                }
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }

    fun read(host: String) {
        viewModelScope.launch {
            run(host, critical = false) {
                val result = session.read()
                _currentProfile.value = result.profileName ?: result.profileID?.let { "编号 $it" }
                val slot = prefs.getInt("targetSlot", 9)
                if (slot in 1..9) {
                    try {
                        session.inspectSlot(slot)
                    } catch (_: Exception) {
                        // slot inspection is best-effort during read
                    }
                }
            }
        }
    }

    fun recoverLegacy(host: String) {
        viewModelScope.launch {
            run(host, critical = true) {
                if (recipeStore.load() != null) {
                    try {
                        session.restorePendingC9(recipeStore)
                    } catch (e: Exception) {
                        append("C9 恢复失败：${e.message}")
                    }
                }
                if (selectionStore.load() != null) {
                    try {
                        session.restorePendingSelection(selectionStore)
                    } catch (e: Exception) {
                        append("设置恢复失败：${e.message}")
                    }
                }
                refreshRecovery()
            }
        }
    }

    fun disconnect() {
        if (_running.value) return
        viewModelScope.launch {
            session.disconnect()
        }
    }

    fun enterBackground() {
        // Non-critical operations are cancelled by tearing down the session;
        // critical transfers keep running so the camera is never left mid-write.
        if (!_critical.value) {
            viewModelScope.launch { session.disconnect() }
        }
    }

    fun onActive() {
        reloadLibrary()
    }

    private suspend fun run(host: String, critical: Boolean, action: suspend () -> Unit) {
        if (_running.value) return
        val cleanHost = host.trim()
        require(cleanHost.isNotEmpty() && !cleanHost.contains("://") && !cleanHost.contains("/") && !cleanHost.contains(":")) {
            throw CameraError.Invalid("请输入有效的相机 IPv4 地址或主机名。")
        }
        _running.value = true
        _critical.value = critical
        _error.value = null
        _report.value = null
        _logs.value = emptyList()
        val guid = prefs.getString("initiatorGUID", null)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: UUID.randomUUID()
        prefs.edit().putString("initiatorGUID", guid.toString()).apply()
        try {
            session.connect(
                host = cleanHost,
                guid = guid,
                requireWifi = false,
                log = { line -> append(line) },
                state = { connected -> _connected.value = connected }
            )
            action()
        } catch (e: Exception) {
            _error.value = e.message
        } finally {
            _running.value = false
            _critical.value = false
            refreshRecovery()
        }
    }

    private fun append(line: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        _logs.value = _logs.value + "$time  $line"
        try {
            File(getApplication<Application>().filesDir, "LatestDiagnostics.txt")
                .writeText(_logs.value.joinToString("\n"))
        } catch (_: Exception) {
        }
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch { session.disconnect() }
    }
}
