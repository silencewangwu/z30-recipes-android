package com.z30lab.recipes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.viewmodel.compose.viewModel
import com.z30lab.core.PhoneRecipe
import com.z30lab.core.RecipeTransferResult

private val Gold = Color(0xFFF5C24D)
private val GoldDim = Color(0xFF6B613F)
private val Background = Color(0xFF0E1114)
private val CardColor = Color(0xFF171B20)
private val CardBorder = Color(0xFF262C33)
private val TextPrimary = Color(0xFFF2F0EA)
private val TextSecondary = Color(0xFF9BA1A6)
private val Green = Color(0xFF52C41A)
private val Red = Color(0xFFEA6668)

@Composable
fun RecipesApp(viewModel: RecipeLibraryViewModel) {
    val recipes by viewModel.recipes.collectAsState()
    val selectedID by viewModel.selectedID.collectAsState()
    val running by viewModel.running.collectAsState()
    val connected by viewModel.connected.collectAsState()
    val error by viewModel.error.collectAsState()
    val report by viewModel.report.collectAsState()
    val currentProfile by viewModel.currentProfile.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val legacyRecovery by viewModel.legacyRecovery.collectAsState()
    val importNotice by viewModel.importNotice.collectAsState()

    var host by rememberSaveable { mutableStateOf("") }
    var showConnection by rememberSaveable { mutableStateOf(false) }
    var showLogs by rememberSaveable { mutableStateOf(false) }
    var showBatch by rememberSaveable { mutableStateOf(false) }
    var targetSlot by rememberSaveable { mutableStateOf(9) }
    val selected = recipes.firstOrNull { it.id == selectedID }

    val importer = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> viewModel.importFiles(uris) }

    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(
        background = Background,
        surface = CardColor,
        onSurface = TextPrimary,
        onBackground = TextPrimary
    )) {
        Box(modifier = Modifier.fillMaxSize().background(Background)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Header(running, connected, currentProfile)
                }
                item {
                    ConnectionCard(
                        host = host,
                        onHostChange = { host = it },
                        expanded = showConnection,
                        onToggle = { showConnection = !showConnection },
                        running = running,
                        connected = connected,
                        legacyRecovery = legacyRecovery,
                        onConnect = { viewModel.read(host) },
                        onDisconnect = { viewModel.disconnect() },
                        onRecover = { viewModel.recoverLegacy(host) }
                    )
                }
                item {
                    LibraryCard(
                        recipes = recipes,
                        selectedID = selectedID,
                        running = running,
                        notice = importNotice,
                        onSelect = { viewModel.selectRecipe(it) },
                        onDelete = { viewModel.deleteRecipe(it) },
                        onImport = { importer.launch(arrayOf("application/octet-stream")) }
                    )
                }
                item {
                    LoadCard(
                        selected = selected,
                        targetSlot = targetSlot,
                        onSlotChange = { targetSlot = it },
                        running = running,
                        connected = connected,
                        onLoadOne = {
                            selected?.let { viewModel.transfer(host, mapOf(targetSlot to it.id), targetSlot) }
                        },
                        onBatch = { showBatch = true }
                    )
                }
                report?.let { r ->
                    item { ReportCard(r) }
                }
                error?.let {
                    item { ErrorCard(it) { viewModel.dismissError() } }
                }
                item {
                    LogsCard(logs = logs, expanded = showLogs, onToggle = { showLogs = !showLogs })
                }
                item {
                    Text(
                        "Z30 Recipes · Android 移植版 · 由 MIT 许可的 iOS 版移植",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 24.dp)
                    )
                }
            }
        }
    }

    if (showBatch) {
        BatchTransferScreen(
            recipes = recipes,
            initialSlot = targetSlot,
            running = running,
            onDismiss = { showBatch = false },
            onConfirm = { assignments, active ->
                viewModel.transfer(host, assignments, active)
                showBatch = false
            }
        )
    }
}

@Composable
private fun Header(running: Boolean, connected: Boolean, currentProfile: String?) {
    Column(modifier = Modifier.padding(top = 24.dp)) {
        Text(
            "Z30 RECIPES",
            color = Gold,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        Text(
            "尼康 Z30 无线配方管理",
            color = TextSecondary,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 2.dp)
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 10.dp)
        ) {
            if (running) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = Gold
                )
                Spacer(Modifier.width(8.dp))
                Text("正在与相机通信…", color = Gold, fontSize = 13.sp)
            } else {
                Box(
                    Modifier.size(8.dp).background(
                        if (connected) Green else TextSecondary,
                        RoundedCornerShape(4.dp)
                    )
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (connected) "已连接" else "未连接",
                    color = if (connected) Green else TextSecondary,
                    fontSize = 13.sp
                )
            }
            if (currentProfile != null && connected) {
                Spacer(Modifier.width(12.dp))
                Text(currentProfile, color = TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ConnectionCard(
    host: String,
    onHostChange: (String) -> Unit,
    expanded: Boolean,
    onToggle: () -> Unit,
    running: Boolean,
    connected: Boolean,
    legacyRecovery: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onRecover: () -> Unit
) {
    SectionCard(title = "连接设置", subtitle = "相机 Wi-Fi 的 IPv4 地址") {
        Text(
            "在相机 Wi-Fi 设置中启用 Wi-Fi，连接 Z30 的热点，然后输入相机分配的 IPv4 地址（如 192.168.48.112）。" +
                "Android 13+ 首次连接热点时需手动确认。",
            color = TextSecondary,
            fontSize = 13.sp,
            lineHeight = 19.sp
        )
        OutlinedTextField(
            value = host,
            onValueChange = onHostChange,
            placeholder = { Text("192.168.48.112", color = TextSecondary) },
            enabled = !running,
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = TextPrimary)
        )
        Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onConnect,
                enabled = !running && host.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Color(0xFF1A1508))
            ) {
                Icon(Icons.Default.PlayArrow, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (connected) "重新连接并读取" else "连接并读取")
            }
            OutlinedButton(onClick = onDisconnect, enabled = !running && connected) {
                Text("断开", color = TextPrimary)
            }
        }
        if (legacyRecovery) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
            ) {
                Text("检测到上次测试未完成的恢复记录。", color = Gold, fontSize = 13.sp)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onRecover, enabled = !running && host.isNotBlank()) {
                    Text("恢复", color = Gold)
                }
            }
        }
        TextButton(onClick = onToggle, modifier = Modifier.padding(top = 4.dp)) {
            Text(if (expanded) "收起" else "更多说明", color = GoldDim, fontSize = 12.sp)
        }
    }
}

@Composable
private fun LibraryCard(
    recipes: List<PhoneRecipe>,
    selectedID: String?,
    running: Boolean,
    notice: String?,
    onSelect: (PhoneRecipe) -> Unit,
    onDelete: (PhoneRecipe) -> Unit,
    onImport: () -> Unit
) {
    SectionCard(title = "手机配方库", subtitle = "${recipes.size} 个配方") {
        if (notice != null) {
            Text(notice, color = Green, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
        }
        Button(
            onClick = onImport,
            enabled = !running,
            colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Color(0xFF1A1508))
        ) {
            Icon(Icons.Default.Edit, null, Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("批量导入")
        }
        if (recipes.isEmpty()) {
            Text(
                "还没有手机配方。从相机导出 NCP/NP3 文件，或用电脑从本程序对应的 iOS 版导入。",
                color = TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 12.dp)
            )
        } else {
            Column(modifier = Modifier.padding(top = 12.dp)) {
                recipes.forEach { recipe ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                    ) {
                        Icon(
                            if (recipe.id == selectedID) Icons.Default.Check else Icons.Default.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (recipe.id == selectedID) Gold else TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(recipe.name, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text(recipe.summary, color = TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            recipe.transferIssue?.let {
                                Text(it, color = Gold, fontSize = 12.sp, maxLines = 2)
                            }
                        }
                        IconButton(onClick = { onSelect(recipe) }, enabled = !running) {
                            Icon(Icons.Default.Edit, null, tint = GoldDim, modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = { onDelete(recipe) }, enabled = !running) {
                            Icon(Icons.Default.Delete, null, tint = Red, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadCard(
    selected: PhoneRecipe?,
    targetSlot: Int,
    onSlotChange: (Int) -> Unit,
    running: Boolean,
    connected: Boolean,
    onLoadOne: () -> Unit,
    onBatch: () -> Unit
) {
    SectionCard(title = "载入相机", subtitle = "一次替换一个 C 槽，或批量配置 C1–C9") {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        ) {
            Text("目标槽位", color = TextSecondary, fontSize = 13.sp)
            Spacer(Modifier.width(12.dp))
            SlotPicker(slot = targetSlot, onSelect = onSlotChange, enabled = !running)
            Spacer(Modifier.weight(1f))
            Text(
                selected?.name ?: "未选择配方",
                color = if (selected != null) Gold else TextSecondary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onLoadOne,
                enabled = !running && connected && selected != null,
                colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Color(0xFF1A1508))
            ) {
                Text("替换 C$targetSlot 并应用")
            }
            OutlinedButton(onClick = onBatch, enabled = !running && connected) {
                Text("批量配置 C1–C9", color = TextPrimary)
            }
        }
        Text(
            "传输期间请保持应用在前台、手机屏幕常亮，不要切换相机模式或断开 Wi-Fi。写入完成前相机显示“保存中”，属正常现象。",
            color = TextSecondary,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(top = 10.dp)
        )
    }
}

@Composable
private fun SlotPicker(slot: Int, onSelect: (Int) -> Unit, enabled: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled) {
            Text("C$slot", color = TextPrimary)
            Icon(Icons.Default.KeyboardArrowDown, null, tint = TextSecondary, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            (1..9).forEach { s ->
                DropdownMenuItem(
                    text = { Text("C$s") },
                    onClick = {
                        onSelect(s)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun ReportCard(report: RecipeTransferResult) {
    SectionCard(title = "相机载入已确认", subtitle = "逐个槽位回读校验结果") {
        Column {
            report.items.forEach { item ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)
                ) {
                    Text("C${item.slot}", color = Gold, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(10.dp))
                    Text(item.name, color = TextPrimary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.weight(1f))
                    Text("${item.verifiedBytes} B", color = TextSecondary, fontSize = 12.sp)
                }
            }
            Text(
                "已应用到 C${report.activeSlot}。写入判定依据为保存的名称、参数与完整曲线核对通过；" +
                    "相机应用状态与存储数据不同是相机的正常展开行为，不代表写入失败。",
                color = TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun ErrorCard(message: String, onDismiss: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CardColor),
        border = androidx.compose.foundation.BorderStroke(1.dp, Red.copy(alpha = 0.6f)),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Text(message, color = Red, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, null, tint = TextSecondary, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun LogsCard(logs: List<String>, expanded: Boolean, onToggle: () -> Unit) {
    SectionCard(title = "诊断日志", subtitle = "${logs.size} 行") {
        if (logs.isEmpty()) {
            Text("连接相机后显示会话与传输日志。", color = TextSecondary, fontSize = 13.sp)
        } else {
            TextButton(onClick = onToggle) {
                Text(if (expanded) "收起" else "展开", color = GoldDim, fontSize = 12.sp)
            }
            if (expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    logs.forEach { line ->
                        Text(line, color = TextSecondary, fontSize = 11.sp, lineHeight = 16.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, subtitle: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CardColor),
        border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 1.dp))
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}
