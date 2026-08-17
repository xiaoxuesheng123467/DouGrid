@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.qiao.dougrid.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Visibility
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qiao.dougrid.BuildConfig
import com.qiao.dougrid.DouGridUiState
import com.qiao.dougrid.DouGridViewModel
import com.qiao.dougrid.data.AppThemeMode

@Composable
fun SettingsScreen(
    state: DouGridUiState,
    viewModel: DouGridViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var paletteMenu by remember { mutableStateOf(false) }
    var showLicense by rememberSaveable { mutableStateOf(false) }
    var confirmEmptyTrash by rememberSaveable { mutableStateOf(false) }
    val defaultPalette = viewModel.paletteCatalog.summaries.firstOrNull { it.id == state.settings.defaultPaletteId }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("设置") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            item { SettingsSectionTitle("外观") }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    ThemeChip("跟随系统", AppThemeMode.SYSTEM, state.settings.themeMode) {
                        viewModel.updateSettings { it.copy(themeMode = AppThemeMode.SYSTEM) }
                    }
                    ThemeChip("浅色", AppThemeMode.LIGHT, state.settings.themeMode) {
                        viewModel.updateSettings { it.copy(themeMode = AppThemeMode.LIGHT) }
                    }
                    ThemeChip("深色", AppThemeMode.DARK, state.settings.themeMode) {
                        viewModel.updateSettings { it.copy(themeMode = AppThemeMode.DARK) }
                    }
                }
            }
            item { SettingsToggle("显示格内色号", state.settings.showColorCodes) { checked -> viewModel.updateSettings { it.copy(showColorCodes = checked) } } }
            item { SettingsToggle("高对比网格", state.settings.highContrastGrid) { checked -> viewModel.updateSettings { it.copy(highContrastGrid = checked) } } }
            item { SettingsSectionTitle("默认实体板") }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf(29, 30, 32).forEach { size ->
                        FilterChip(
                            selected = state.settings.defaultBoardSize == size,
                            onClick = { viewModel.updateSettings { it.copy(defaultBoardSize = size) } },
                            label = { Text("$size × $size") },
                        )
                    }
                    if (state.settings.defaultBoardSize !in listOf(29, 30, 32)) {
                        FilterChip(
                            selected = true,
                            onClick = {},
                            label = { Text("${state.settings.defaultBoardSize} × ${state.settings.defaultBoardSize}") },
                        )
                    }
                }
            }
            item { SettingsSectionTitle("默认色卡") }
            item {
                androidx.compose.foundation.layout.Box {
                    OutlinedButton(onClick = { paletteMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Palette, null)
                        Spacer(Modifier.size(8.dp))
                        Text(defaultPalette?.let { "${it.title} · ${it.colorCount} 色" } ?: "选择色卡", modifier = Modifier.weight(1f))
                        Icon(Icons.Default.ChevronRight, null)
                    }
                    DropdownMenu(expanded = paletteMenu, onDismissRequest = { paletteMenu = false }) {
                        viewModel.paletteCatalog.summaries.forEach { palette ->
                            DropdownMenuItem(
                                text = { Text("${palette.title} · ${palette.colorCount} 色") },
                                onClick = {
                                    paletteMenu = false
                                    viewModel.updateSettings { it.copy(defaultPaletteId = palette.id) }
                                },
                            )
                        }
                    }
                }
            }
            item { SettingsSectionTitle("开拼") }
            item { SettingsToggle("制作时保持亮屏", state.settings.keepScreenOnInCraftMode) { checked -> viewModel.updateSettings { it.copy(keepScreenOnInCraftMode = checked) } } }
            item { SettingsToggle("扣减库存前确认", state.settings.confirmInventoryDeduction) { checked -> viewModel.updateSettings { it.copy(confirmInventoryDeduction = checked) } } }
            item { SettingsSectionTitle("低库存提醒") }
            item {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(0 to "关闭", 100 to "≤ 100", 300 to "≤ 300", 500 to "≤ 500").forEach { (threshold, label) ->
                        FilterChip(
                            selected = state.settings.lowStockThreshold == threshold,
                            onClick = { viewModel.updateSettings { it.copy(lowStockThreshold = threshold) } },
                            label = { Text(label) },
                        )
                    }
                }
            }
            item { SettingsSectionTitle("数据") }
            item {
                SettingsActionRow(
                    icon = Icons.Default.DeleteSweep,
                    title = "回收站",
                    detail = "${state.deletedProjects.size} 个作品",
                    enabled = state.deletedProjects.isNotEmpty(),
                    onClick = { confirmEmptyTrash = true },
                )
            }
            item { SettingsSectionTitle("色卡与许可") }
            item {
                SettingsActionRow(
                    icon = Icons.Default.ColorLens,
                    title = "色卡数据来源",
                    detail = "pindou-color-data · MIT",
                    onClick = { showLicense = true },
                )
            }
            item {
                SettingsActionRow(
                    icon = Icons.Default.Visibility,
                    title = "屏幕颜色仅供参考",
                    detail = "实物会受批次、光线和显示设备影响",
                    onClick = { showLicense = true },
                )
            }
            item { SettingsSectionTitle("关于") }
            item {
                SettingsActionRow(
                    icon = Icons.Default.Info,
                    title = "乔格 ${BuildConfig.VERSION_NAME}",
                    detail = "从灵感到成品的拼豆工作台",
                    onClick = { showLicense = true },
                )
            }
            item { Spacer(Modifier.size(32.dp)) }
        }
    }

    if (confirmEmptyTrash) {
        AlertDialog(
            onDismissRequest = { confirmEmptyTrash = false },
            title = { Text("清空回收站") },
            text = { Text("${state.deletedProjects.size} 个作品将永久删除。") },
            confirmButton = { Button(onClick = { viewModel.emptyTrash(); confirmEmptyTrash = false }) { Text("永久删除") } },
            dismissButton = { TextButton(onClick = { confirmEmptyTrash = false }) { Text("取消") } },
        )
    }
    if (showLicense) {
        val license = remember {
            runCatching { context.assets.open("palettes/HANSBUG_LICENSE.txt").bufferedReader().use { it.readText() } }
                .getOrDefault("MIT License")
        }
        AlertDialog(
            onDismissRequest = { showLicense = false },
            title = { Text("色卡说明") },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    item { Text("品牌名称仅用于说明色卡兼容性，乔格与相关品牌无官方关联。", style = MaterialTheme.typography.bodyMedium) }
                    item { Spacer(Modifier.size(12.dp)) }
                    item { Text(license, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = { TextButton(onClick = { showLicense = false }) { Text("关闭") } },
        )
    }
}

@Composable
private fun SettingsSectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp),
    )
}

@Composable
private fun ThemeChip(label: String, mode: AppThemeMode, selected: AppThemeMode, onClick: () -> Unit) {
    FilterChip(selected = mode == selected, onClick = onClick, label = { Text(label) })
}

@Composable
private fun SettingsToggle(title: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChecked)
    }
    HorizontalDivider()
}

@Composable
private fun SettingsActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    detail: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
    }
    HorizontalDivider()
}
