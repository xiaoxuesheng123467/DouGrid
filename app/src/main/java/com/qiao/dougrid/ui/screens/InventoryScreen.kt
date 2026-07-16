@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.qiao.dougrid.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qiao.dougrid.DouGridUiState
import com.qiao.dougrid.DouGridViewModel
import com.qiao.dougrid.core.BeadPalette
import com.qiao.dougrid.data.BeadProject
import com.qiao.dougrid.data.InventoryEntry
import com.qiao.dougrid.data.MainDestination
import com.qiao.dougrid.export.MaterialPlanner

private enum class InventoryFilter { REQUIRED, SHORTAGE, STOCKED, ALL }

@Composable
fun InventoryScreen(state: DouGridUiState, viewModel: DouGridViewModel) {
    val context = LocalContext.current
    val usableProjects = state.projects.filter { it.grid.beadCount() > 0 }
    var projectId by rememberSaveable { mutableStateOf(state.activeCraftProjectId ?: usableProjects.firstOrNull()?.id) }
    var paletteId by rememberSaveable { mutableStateOf(usableProjects.firstOrNull { it.id == projectId }?.paletteId ?: state.settings.defaultPaletteId) }
    var projectMenu by remember { mutableStateOf(false) }
    var paletteMenu by remember { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    var filterName by rememberSaveable { mutableStateOf(InventoryFilter.REQUIRED.name) }
    var editingColorIndex by remember { mutableStateOf<Int?>(null) }
    val filter = InventoryFilter.valueOf(filterName)
    val palette = viewModel.palette(paletteId)
    val project = usableProjects.firstOrNull { it.id == projectId && it.paletteId == paletteId }
    val required = project?.grid?.colorCounts().orEmpty()
    val inventoryByCode = state.inventory.filter { it.paletteId == paletteId }.associateBy { it.colorCode }
    val rows = palette.colors.indices.filter { index ->
        val color = palette.colors[index]
        val onHand = inventoryByCode[color.code]?.onHand ?: 0
        val needed = required[index] ?: 0
        val matchesSearch = search.isBlank() || color.code.contains(search, true) || color.name.contains(search, true) || color.group.contains(search, true)
        val matchesFilter = when (filter) {
            InventoryFilter.REQUIRED -> needed > 0
            InventoryFilter.SHORTAGE -> needed > onHand
            InventoryFilter.STOCKED -> onHand > 0
            InventoryFilter.ALL -> true
        }
        matchesSearch && matchesFilter
    }
    val totalStock = inventoryByCode.values.sumOf { it.onHand }
    val totalNeeded = required.values.sum()
    val totalShortage = required.entries.sumOf { (index, count) ->
        val code = palette.colors.getOrNull(index)?.code ?: return@sumOf 0
        (count - (inventoryByCode[code]?.onHand ?: 0)).coerceAtLeast(0)
    }

    LaunchedEffect(state.activeCraftProjectId, state.mainDestination) {
        val requested = usableProjects.firstOrNull { it.id == state.activeCraftProjectId }
        if (state.mainDestination == MainDestination.INVENTORY && requested != null) {
            projectId = requested.id
            paletteId = requested.paletteId
            filterName = InventoryFilter.REQUIRED.name
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text("豆仓")
                    Text("${palette.title} · ${inventoryByCode.count { it.value.onHand > 0 }} 色", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            actions = {
                if (project != null && totalShortage > 0) {
                    IconButton(onClick = {
                        val text = MaterialPlanner.procurementListText(project, palette, state.inventory)
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "${project.title}采购单")
                            putExtra(Intent.EXTRA_TEXT, text)
                        }, "分享采购单"))
                    }) { Icon(Icons.Default.Share, contentDescription = "分享采购单") }
                }
            },
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) {
                        OutlinedButton(onClick = { projectMenu = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(project?.title ?: "选择备料项目", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Icon(Icons.Default.ExpandMore, null)
                        }
                        DropdownMenu(expanded = projectMenu, onDismissRequest = { projectMenu = false }) {
                            DropdownMenuItem(text = { Text("不关联项目") }, onClick = { projectId = null; projectMenu = false; filterName = InventoryFilter.STOCKED.name })
                            usableProjects.forEach { item ->
                                DropdownMenuItem(
                                    text = { Text(item.title) },
                                    onClick = {
                                        projectId = item.id
                                        paletteId = item.paletteId
                                        filterName = InventoryFilter.REQUIRED.name
                                        projectMenu = false
                                    },
                                )
                            }
                        }
                    }
                    Box(Modifier.weight(1f)) {
                        OutlinedButton(onClick = { paletteMenu = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(palette.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Icon(Icons.Default.ExpandMore, null)
                        }
                        DropdownMenu(expanded = paletteMenu, onDismissRequest = { paletteMenu = false }) {
                            viewModel.paletteCatalog.summaries.forEach { summary ->
                                DropdownMenuItem(
                                    text = { Text("${summary.title} · ${summary.colorCount} 色") },
                                    onClick = { paletteId = summary.id; projectId = null; paletteMenu = false; filterName = InventoryFilter.STOCKED.name },
                                )
                            }
                        }
                    }
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                ) {
                    InventoryStat("现有", totalStock, MaterialTheme.colorScheme.primary)
                    InventoryStat("需要", totalNeeded, MaterialTheme.colorScheme.secondary)
                    InventoryStat("缺少", totalShortage, if (totalShortage > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
            }
            item {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    label = { Text("搜索色号") },
                    leadingIcon = { Icon(Icons.Default.Inventory2, null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    FilterChip(selected = filter == InventoryFilter.REQUIRED, onClick = { filterName = InventoryFilter.REQUIRED.name }, label = { Text("需要") }, enabled = project != null)
                    FilterChip(selected = filter == InventoryFilter.SHORTAGE, onClick = { filterName = InventoryFilter.SHORTAGE.name }, label = { Text("缺货") }, enabled = project != null)
                    FilterChip(selected = filter == InventoryFilter.STOCKED, onClick = { filterName = InventoryFilter.STOCKED.name }, label = { Text("有库存") })
                    FilterChip(selected = filter == InventoryFilter.ALL, onClick = { filterName = InventoryFilter.ALL.name }, label = { Text("全部") })
                }
                HorizontalDivider()
            }
            if (rows.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(Icons.Default.Inventory2, null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.size(10.dp))
                        Text("当前筛选没有颜色", style = MaterialTheme.typography.titleMedium)
                    }
                }
            } else {
                itemsIndexed(rows, key = { _, index -> palette.colors[index].code }) { _, index ->
                    val color = palette.colors[index]
                    val entry = inventoryByCode[color.code] ?: InventoryEntry(paletteId, color.code, 0)
                    val needed = required[index] ?: 0
                    val shortage = (needed - entry.onHand).coerceAtLeast(0)
                    InventoryColorRow(
                        color = Color(color.opaqueArgb),
                        code = color.code,
                        name = color.name,
                        onHand = entry.onHand,
                        needed = needed,
                        shortage = shortage,
                        onMinus = { viewModel.updateInventory(paletteId, color.code, (entry.onHand - 100).coerceAtLeast(0)) },
                        onPlus = { viewModel.updateInventory(paletteId, color.code, entry.onHand + 100) },
                        onEdit = { editingColorIndex = index },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    editingColorIndex?.let { index ->
        val color = palette.colors[index]
        val entry = inventoryByCode[color.code] ?: InventoryEntry(paletteId, color.code, 0)
        InventoryEditDialog(
            code = color.code,
            initialAmount = entry.onHand,
            initialBagSize = entry.bagSize,
            onDismiss = { editingColorIndex = null },
            onSave = { amount, bag ->
                viewModel.updateInventory(paletteId, color.code, amount, bag)
                editingColorIndex = null
            },
        )
    }
}

@Composable
private fun InventoryStat(label: String, value: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value.toString(), style = MaterialTheme.typography.headlineSmall, color = color, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun InventoryColorRow(
    color: Color,
    code: String,
    name: String,
    onHand: Int,
    needed: Int,
    shortage: Int,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    onEdit: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(34.dp).clip(MaterialTheme.shapes.small).background(color))
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Text(code, fontWeight = FontWeight.SemiBold)
            Text(
                if (needed > 0) {
                    "需要 $needed${if (shortage > 0) " · 缺 $shortage" else " · 已够"}"
                } else {
                    name.takeUnless { it.equals(code, ignoreCase = true) } ?: "未用于当前项目"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (shortage > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onMinus, enabled = onHand > 0) { Icon(Icons.Default.Remove, "减少 100") }
        TextButton(onClick = onEdit, modifier = Modifier.size(width = 72.dp, height = 44.dp)) {
            Text(onHand.toString(), fontWeight = FontWeight.Bold)
        }
        IconButton(onClick = onPlus) { Icon(Icons.Default.Add, "增加 100") }
    }
}

@Composable
private fun InventoryEditDialog(
    code: String,
    initialAmount: Int,
    initialBagSize: Int,
    onDismiss: () -> Unit,
    onSave: (Int, Int) -> Unit,
) {
    var amount by rememberSaveable { mutableStateOf(initialAmount.toString()) }
    var bagSize by rememberSaveable { mutableStateOf(initialBagSize.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$code 库存") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it.filter(Char::isDigit).take(6) },
                    label = { Text("现有颗数") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = bagSize,
                    onValueChange = { bagSize = it.filter(Char::isDigit).take(6) },
                    label = { Text("每袋颗数") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(amount.toIntOrNull() ?: 0, bagSize.toIntOrNull()?.coerceAtLeast(1) ?: 1_000) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
