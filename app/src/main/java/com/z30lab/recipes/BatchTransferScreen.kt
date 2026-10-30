package com.z30lab.recipes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.z30lab.core.PhoneRecipe

private val Gold = Color(0xFFF5C24D)
private val Background = Color(0xFF0E1114)
private val CardColor = Color(0xFF171B20)
private val CardBorder = Color(0xFF262C33)
private val TextPrimary = Color(0xFFF2F0EA)
private val TextSecondary = Color(0xFF9BA1A6)
private val Red = Color(0xFFEA6668)

/** Full-screen batch composer mirroring the iOS BatchTransferView: choose a
 *  phone recipe per C slot, pick the slot to apply, then write all at once. */
@Composable
fun BatchTransferScreen(
    recipes: List<PhoneRecipe>,
    initialSlot: Int,
    running: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Map<Int, String>, Int) -> Unit
) {
    val slotState = remember { mutableStateMapOf<Int, String>().apply { put(initialSlot, "") } }
    var activeSlot by remember { mutableStateOf(initialSlot) }
    var missing by remember { mutableStateOf<String?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Background)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(top = 18.dp)
                    ) {
                        Text(
                            "批量配置 C1–C9",
                            color = TextPrimary,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, null, tint = TextSecondary)
                        }
                    }
                    Text(
                        "为每个槽位选择要写入的配方。留空的槽位保持相机当前内容不变。写入按 C1 到 C9 顺序进行。",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    )
                }
                items((1..9).toList()) { slot ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(CardColor, RoundedCornerShape(12.dp))
                            .border(1.dp, CardBorder, RoundedCornerShape(12.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Text("C$slot", color = Gold, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(14.dp))
                        RecipePicker(
                            recipes = recipes,
                            selected = slotState[slot],
                            onSelect = { slotState[slot] = it },
                            modifier = Modifier.weight(1f)
                        )
                        if (slot == activeSlot) {
                            Spacer(Modifier.width(10.dp))
                            Text("应用此槽位", color = Gold, fontSize = 12.sp)
                        }
                    }
                }
                item {
                    Text(
                        "应用槽位",
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Row(modifier = Modifier.padding(top = 6.dp)) {
                        SlotPicker(slot = activeSlot, onSelect = { activeSlot = it }, enabled = !running)
                    }
                }
                missing?.let {
                    item {
                        Text(it, color = Red, fontSize = 13.sp)
                    }
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(onClick = onDismiss, enabled = !running) {
                            Text("取消", color = TextPrimary)
                        }
                        Button(
                            onClick = {
                                val unset = slotState.filterValues { it.isEmpty() }.keys
                                if (unset.isNotEmpty()) {
                                    missing = "请为 C${unset.joinToString(", C")} 选择配方，或取消选择该槽位。"
                                } else {
                                    onConfirm(slotState.toMap(), activeSlot)
                                }
                            },
                            enabled = !running,
                            colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Color(0xFF1A1508)),
                            modifier = Modifier.weight(1f)
                        ) {
                            if (running) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color(0xFF1A1508))
                            } else {
                                Text("同步 ${slotState.values.count { it.isNotEmpty() }} 个槽位")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecipePicker(
    recipes: List<PhoneRecipe>,
    selected: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                recipes.firstOrNull { it.id == selected }?.name ?: "未选择",
                color = if (selected != null) TextPrimary else TextSecondary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Icon(Icons.Default.KeyboardArrowDown, null, tint = TextSecondary, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("未选择", color = TextSecondary) },
                onClick = {
                    onSelect("")
                    expanded = false
                }
            )
            recipes.forEach { recipe ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(recipe.name, color = TextPrimary, fontSize = 14.sp)
                            Text(recipe.summary, color = TextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    onClick = {
                        onSelect(recipe.id)
                        expanded = false
                    }
                )
            }
        }
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
