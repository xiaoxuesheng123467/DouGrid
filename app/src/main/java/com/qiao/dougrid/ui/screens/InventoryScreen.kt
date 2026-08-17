@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.qiao.dougrid.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddBox
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MoveToInbox
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import com.qiao.dougrid.data.InventoryEntry
import com.qiao.dougrid.data.MainDestination
import com.qiao.dougrid.export.MaterialPlanner

private enum class InventoryFilter { REQUIRED, SHORTAGE, LOW_STOCK, STOCKED, ALL }

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
    var inventoryMenu by remember { mutableStateOf(false) }
    var pendingImportUriString by rememberSaveable { mutableStateOf<String?>(null) }
    val importCsvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        pendingImportUriString = uri.toString()
    }
    val exportCsvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let(viewModel::exportInventoryCsv)
    }
    val filter = InventoryFilter.valueOf(filterName)
    val palette = viewModel.palette(paletteId)
    val project = usableProjects.firstOrNull { it.id == projectId && it.paletteId == paletteId }
    val required = project?.grid?.colorCounts().orEmpty()
    val paletteCodes = palette.colors.mapTo(hashSetOf()) { it.code }
    val inventoryByCode = state.inventory
        .filter { it.paletteId == paletteId && it.colorCode in paletteCodes }
        .associateBy { it.colorCode }
    val lowStockThreshold = state.settings.lowStockThreshold
    val lowStockCount = inventoryByCode.values.count {
        lowStockThreshold > 0 && it.onHand in 1..lowStockThreshold
    }
    val rows = palette.colors.indices.filter { index ->
        val color = palette.colors[index]
        val onHand = inventoryByCode[color.code]?.onHand ?: 0
        val needed = required[index] ?: 0
        val matchesSearch = search.isBlank() || color.code.contains(search, true) || color.name.contains(search, true) || color.group.contains(search, true)
        val matchesFilter = when (filter) {
            InventoryFilter.REQUIRED -> needed > 0
            InventoryFilter.SHORTAGE -> needed > onHand
            InventoryFilter.LOW_STOCK -> lowStockThreshold > 0 && onHand in 1..lowStockThreshold
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
    val bagsToReceive = remember(project, palette, state.inventory) {
        project?.let { selectedProject ->
            MaterialPlanner.plan(selectedProject, palette, state.inventory).sumOf { it.bagsToBuy }
        } ?: 0
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
                    Text(
                        "${palette.title} · ${inventoryByCode.count { it.value.onHand > 0 }} 色" +
                            if (lowStockCount > 0) " · $lowStockCount 色偏低" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
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
                Box {
                    IconButton(onClick = { inventoryMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "豆仓文件")
                    }
                    DropdownMenu(expanded = inventoryMenu, onDismissRequest = { inventoryMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("导入豆仓 CSV") },
                            leadingIcon = { Icon(Icons.Default.FileUpload, contentDescription = null) },
                            onClick = {
                                inventoryMenu = false
                                importCsvLauncher.launch(
                                    arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/csv"),
                                )
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("导出全部豆仓 CSV") },
                            leadingIcon = { Icon(Icons.Default.FileDownload, contentDescription = null) },
                            onClick = {
                                inventoryMenu = false
                                exportCsvLauncher.launch("乔格-豆仓.csv")
                            },
                        )
                    }
                }
            },
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            item {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val compact = maxWidth < 520.dp
                    val projectSelector: @Composable (Modifier) -> Unit = { selectorModifier ->
                        InventorySelector(
                            label = project?.title ?: "选择备料项目",
                            expanded = projectMenu,
                            onExpandedChange = { projectMenu = it },
                            modifier = selectorModifier,
                        ) {
                            DropdownMenuItem(
                                text = { Text("不关联项目") },
                                onClick = {
                                    projectId = null
                                    projectMenu = false
                                    filterName = InventoryFilter.STOCKED.name
                                },
                            )
                            usableProjects.forEach { item ->
                                DropdownMenuItem(
                                    text = { Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
                    val paletteSelector: @Composable (Modifier) -> Unit = { selectorModifier ->
                        InventorySelector(
                            label = palette.title,
                            expanded = paletteMenu,
                            onExpandedChange = { paletteMenu = it },
                            modifier = selectorModifier,
                        ) {
                            viewModel.paletteCatalog.summaries.forEach { summary ->
                                DropdownMenuItem(
                                    text = { Text("${summary.title} · ${summary.colorCount} 色") },
                                    onClick = {
                                        paletteId = summary.id
                                        projectId = null
                                        paletteMenu = false
                                        filterName = InventoryFilter.STOCKED.name
                                    },
                                )
                            }
                        }
                    }
                    if (compact) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            projectSelector(Modifier.fillMaxWidth())
                            paletteSelector(Modifier.fillMaxWidth())
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            projectSelector(Modifier.weight(1f))
                            paletteSelector(Modifier.weight(1f))
                        }
                    }
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                ) {
                    InventoryStat("现有", totalStock, MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                    InventoryStat("需要", totalNeeded, MaterialTheme.colorScheme.secondary, Modifier.weight(1f))
                    InventoryStat(
                        "缺少",
                        totalShortage,
                        if (totalShortage > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        Modifier.weight(1f),
                    )
                }
            }
            if (project != null && bagsToReceive > 0) {
                item {
                    PurchaseReceiveAction(
                        projectTitle = project.title,
                        shortage = totalShortage,
                        bags = bagsToReceive,
                        onReceive = { viewModel.receiveProjectPurchases(project.id) },
                    )
                    Spacer(Modifier.size(10.dp))
                }
            }
            if (lowStockCount > 0) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                            Spacer(Modifier.size(8.dp))
                            Text(
                                "$lowStockCount 个颜色低于或等于 $lowStockThreshold 颗",
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { filterName = InventoryFilter.LOW_STOCK.name }) { Text("查看") }
                        }
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    label = { Text("搜索色号、名称或色系") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = search.takeIf(String::isNotBlank)?.let {
                        {
                            IconButton(onClick = { search = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "清除搜索")
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    FilterChip(selected = filter == InventoryFilter.REQUIRED, onClick = { filterName = InventoryFilter.REQUIRED.name }, label = { Text("需要") }, enabled = project != null)
                    FilterChip(selected = filter == InventoryFilter.SHORTAGE, onClick = { filterName = InventoryFilter.SHORTAGE.name }, label = { Text("缺货") }, enabled = project != null)
                    FilterChip(selected = filter == InventoryFilter.LOW_STOCK, onClick = { filterName = InventoryFilter.LOW_STOCK.name }, label = { Text("低库存") }, enabled = lowStockThreshold > 0)
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
                        bagSize = entry.bagSize,
                        onMinus = { viewModel.updateInventory(paletteId, color.code, (entry.onHand - 100).coerceAtLeast(0)) },
                        onPlus = { viewModel.updateInventory(paletteId, color.code, entry.onHand + 100) },
                        onAddBag = {
                            val amount = (entry.onHand.toLong() + entry.bagSize).coerceAtMost(999_999L).toInt()
                            viewModel.updateInventory(paletteId, color.code, amount)
                        },
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

    pendingImportUriString?.let { rawUri ->
        val uri = Uri.parse(rawUri)
        InventoryCsvImportDialog(
            onDismiss = { pendingImportUriString = null },
            onMerge = {
                pendingImportUriString = null
                viewModel.importInventoryCsv(uri, replace = false)
            },
            onReplace = {
                pendingImportUriString = null
                viewModel.importInventoryCsv(uri, replace = true)
            },
        )
    }
}

@Composable
private fun InventorySelector(
    label: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    menuContent: @Composable () -> Unit,
) {
    Box(modifier) {
        OutlinedButton(
            onClick = { onExpandedChange(true) },
            modifier = Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 14.dp, end = 8.dp),
        ) {
            Text(label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Default.ExpandMore, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            menuContent()
        }
    }
}

@Composable
private fun InventoryStat(label: String, value: Int, color: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value.toString(),
            style = MaterialTheme.typography.titleLarge,
            color = color,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PurchaseReceiveAction(
    projectTitle: String,
    shortage: Int,
    bags: Int,
    onReceive: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            if (maxWidth < 420.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PurchaseReceiveDetails(projectTitle, shortage, bags)
                    FilledTonalButton(onClick = onReceive, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.MoveToInbox, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("到货入库")
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PurchaseReceiveDetails(projectTitle, shortage, bags, Modifier.weight(1f))
                    Spacer(Modifier.size(12.dp))
                    FilledTonalButton(onClick = onReceive) {
                        Icon(Icons.Default.MoveToInbox, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("到货入库")
                    }
                }
            }
        }
    }
}

@Composable
private fun PurchaseReceiveDetails(
    projectTitle: String,
    shortage: Int,
    bags: Int,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(projectTitle, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            "缺 $shortage 颗 · 采购到货 $bags 袋",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.78f),
        )
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
    bagSize: Int,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    onAddBag: () -> Unit,
    onEdit: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        if (maxWidth < 520.dp) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(34.dp).clip(MaterialTheme.shapes.small).background(color))
                    Spacer(Modifier.size(10.dp))
                    InventoryColorDetails(
                        code = code,
                        name = name,
                        needed = needed,
                        shortage = shortage,
                        bagSize = bagSize,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                    )
                    Spacer(Modifier.size(8.dp))
                    InventoryAmountButton(onHand = onHand, onEdit = onEdit)
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 44.dp, top = 3.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    InventoryQuantityActions(
                        onHand = onHand,
                        showAmount = false,
                        onMinus = onMinus,
                        onPlus = onPlus,
                        onAddBag = onAddBag,
                        onEdit = onEdit,
                    )
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).clip(MaterialTheme.shapes.small).background(color))
                Spacer(Modifier.size(10.dp))
                InventoryColorDetails(
                    code = code,
                    name = name,
                    needed = needed,
                    shortage = shortage,
                    bagSize = bagSize,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                InventoryQuantityActions(
                    onHand = onHand,
                    showAmount = true,
                    onMinus = onMinus,
                    onPlus = onPlus,
                    onAddBag = onAddBag,
                    onEdit = onEdit,
                )
            }
        }
    }
}

@Composable
private fun InventoryColorDetails(
    code: String,
    name: String,
    needed: Int,
    shortage: Int,
    bagSize: Int,
    modifier: Modifier = Modifier,
    maxLines: Int,
) {
    Column(modifier) {
        Text(code, fontWeight = FontWeight.SemiBold)
        val status = if (needed > 0) {
            "需要 $needed${if (shortage > 0) " · 缺 $shortage" else " · 已够"}"
        } else {
            name.takeUnless { it.equals(code, ignoreCase = true) } ?: "未用于当前项目"
        }
        Text(
            "$status · 每袋 $bagSize",
            style = MaterialTheme.typography.bodySmall,
            color = if (shortage > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun InventoryQuantityActions(
    onHand: Int,
    showAmount: Boolean,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    onAddBag: () -> Unit,
    onEdit: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onMinus, enabled = onHand > 0, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.Remove, contentDescription = "减少 100")
        }
        if (showAmount) InventoryAmountButton(onHand = onHand, onEdit = onEdit)
        IconButton(onClick = onPlus, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.Add, contentDescription = "增加 100")
        }
        TextButton(
            onClick = onAddBag,
            modifier = Modifier.heightIn(min = 48.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
        ) {
            Icon(Icons.Default.AddBox, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(4.dp))
            Text("+1 袋", maxLines = 1)
        }
    }
}

@Composable
private fun InventoryAmountButton(onHand: Int, onEdit: () -> Unit) {
    OutlinedButton(
        onClick = onEdit,
        modifier = Modifier.size(width = 82.dp, height = 48.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp),
    ) {
        Text(onHand.toString(), fontWeight = FontWeight.Bold, maxLines = 1)
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

@Composable
private fun InventoryCsvImportDialog(
    onDismiss: () -> Unit,
    onMerge: () -> Unit,
    onReplace: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入豆仓 CSV") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("选择 CSV 数据的处理方式。导入只在本机完成。")
                FilledTonalButton(
                    onClick = onMerge,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("合并现有豆仓（MERGE）")
                }
                Text(
                    "保留现有记录；CSV 中相同色卡和色号会更新。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = onReplace,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("替换全部豆仓（REPLACE）")
                }
                Text(
                    "会先清空所有现有豆仓记录，再写入这份 CSV。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
