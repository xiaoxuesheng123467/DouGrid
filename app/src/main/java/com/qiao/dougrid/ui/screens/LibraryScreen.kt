@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.qiao.dougrid.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.qiao.dougrid.DouGridUiState
import com.qiao.dougrid.DouGridViewModel
import com.qiao.dougrid.data.BeadProject
import com.qiao.dougrid.data.BeadTemplate
import com.qiao.dougrid.data.ProjectStatus
import com.qiao.dougrid.ui.components.PatternThumbnail
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LibraryScreen(
    state: DouGridUiState,
    viewModel: DouGridViewModel,
    onImportUri: (Uri) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    var search by rememberSaveable { mutableStateOf("") }
    var selectedCategory by rememberSaveable { mutableStateOf<String?>(null) }
    var showBlankDialog by rememberSaveable { mutableStateOf(false) }
    var showTrash by rememberSaveable { mutableStateOf(false) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(onImportUri)
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) cameraUri?.let(onImportUri)
    }
    val query = search.trim()
    val categories = state.templates.map(BeadTemplate::category).distinct()
    val activeCategory = selectedCategory?.takeIf { it in categories }
    val projects = state.projects.filter { query.isBlank() || it.title.contains(query, ignoreCase = true) }
    val templates = state.templates.filter {
        (activeCategory == null || it.category == activeCategory) &&
            (query.isBlank() || it.title.contains(query, ignoreCase = true) || it.category.contains(query, ignoreCase = true))
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text("豆格", style = MaterialTheme.typography.titleLarge)
                    Text("${state.projects.size} 个作品", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            actions = {
                if (state.deletedProjects.isNotEmpty()) {
                    IconButton(onClick = { showTrash = true }) {
                        Icon(Icons.Default.RestoreFromTrash, contentDescription = "回收站")
                    }
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "设置")
                }
            },
        )

        LazyVerticalGrid(
            columns = GridCells.Adaptive(168.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("搜索作品和模板") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalButton(
                        onClick = {
                            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("相册")
                    }
                    FilledTonalButton(
                        onClick = {
                            val directory = File(context.cacheDir, "images").apply { mkdirs() }
                            val file = File.createTempFile("dougrid-camera-", ".jpg", directory)
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                            cameraUri = uri
                            camera.launch(uri)
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("拍照")
                    }
                    OutlinedButton(
                        onClick = { showBlankDialog = true },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("空白")
                    }
                }
            }

            item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("我的作品", projects.size) }
            if (projects.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyLibraryState(hasQuery = query.isNotBlank(), onCreate = { showBlankDialog = true })
                }
            } else {
                items(projects, key = { it.id }) { project ->
                    ProjectCard(
                        project = project,
                        palette = viewModel.palette(project.paletteId),
                        revision = state.editorRevision,
                        onOpen = { viewModel.requestOpenProject(project.id) },
                        onFavorite = { viewModel.toggleFavorite(project.id) },
                        onDuplicate = { viewModel.duplicateProject(project.id) },
                        onDelete = { viewModel.deleteProject(project.id) },
                    )
                }
            }

            item(span = { GridItemSpan(maxLineSpan) }) {
                Spacer(Modifier.height(4.dp))
                HorizontalDivider()
                SectionTitle("灵感模板", templates.size)
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item(key = "all") {
                        FilterChip(
                            selected = activeCategory == null,
                            onClick = { selectedCategory = null },
                            label = { Text("全部") },
                        )
                    }
                    categories.forEach { category ->
                        item(key = category) {
                            FilterChip(
                                selected = activeCategory == category,
                                onClick = { selectedCategory = category },
                                label = { Text(category) },
                            )
                        }
                    }
                }
            }
            items(templates, key = { it.id }) { template ->
                TemplateCard(
                    template = template,
                    palette = viewModel.palette(template.paletteId),
                    onUse = { viewModel.createFromTemplate(template.id) },
                )
            }
        }
    }

    if (showBlankDialog) {
        BlankProjectDialog(
            state = state,
            viewModel = viewModel,
            onDismiss = { showBlankDialog = false },
            onCreate = { title, width, height, paletteId ->
                showBlankDialog = false
                viewModel.createBlank(title, width, height, paletteId)
            },
        )
    }
    if (showTrash) {
        TrashDialog(
            projects = state.deletedProjects,
            onRestore = viewModel::restoreProject,
            onEmpty = viewModel::emptyTrash,
            onDismiss = { showTrash = false },
        )
    }
}

@Composable
private fun SectionTitle(title: String, count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Text(count.toString(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ProjectCard(
    project: BeadProject,
    palette: com.qiao.dougrid.core.BeadPalette,
    revision: Long,
    onOpen: () -> Unit,
    onFavorite: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Card(
        onClick = onOpen,
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        PatternThumbnail(
            grid = project.grid,
            palette = palette,
            revision = revision,
            modifier = Modifier.fillMaxWidth().aspectRatio(1.18f),
        )
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    project.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.MoreVert, contentDescription = "项目操作", modifier = Modifier.size(18.dp))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(if (project.favorite) "取消收藏" else "收藏") },
                            leadingIcon = { Icon(if (project.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null) },
                            onClick = { menu = false; onFavorite() },
                        )
                        DropdownMenuItem(
                            text = { Text("创建副本") },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                            onClick = { menu = false; onDuplicate() },
                        )
                        DropdownMenuItem(
                            text = { Text("移到回收站") },
                            leadingIcon = { Icon(Icons.Default.Delete, null) },
                            onClick = { menu = false; onDelete() },
                        )
                    }
                }
            }
            Text(
                "${project.grid.width} × ${project.grid.height} · ${project.grid.beadCount()} 颗",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(statusLabel(project.status), style = MaterialTheme.typography.labelSmall, color = statusColor(project.status))
                Spacer(Modifier.weight(1f))
                Text(formatDate(project.modifiedAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (project.status == ProjectStatus.CRAFTING || project.progress > 0f) {
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { project.progress },
                    modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun TemplateCard(
    template: BeadTemplate,
    palette: com.qiao.dougrid.core.BeadPalette,
    onUse: () -> Unit,
) {
    Card(
        onClick = onUse,
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        PatternThumbnail(
            grid = template.grid,
            palette = palette,
            revision = 0,
            modifier = Modifier.fillMaxWidth().aspectRatio(1.18f),
        )
        Column(Modifier.padding(12.dp)) {
            Text(template.title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Text(
                "${template.category} · ${template.grid.width} × ${template.grid.height}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyLibraryState(hasQuery: Boolean, onCreate: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
        Text(if (hasQuery) "没有匹配的作品" else "还没有作品", style = MaterialTheme.typography.titleMedium)
        if (!hasQuery) Button(onClick = onCreate) { Text("新建空白图纸") }
    }
}

@Composable
private fun BlankProjectDialog(
    state: DouGridUiState,
    viewModel: DouGridViewModel,
    onDismiss: () -> Unit,
    onCreate: (String, Int, Int, String) -> Unit,
) {
    var title by rememberSaveable { mutableStateOf("新图纸") }
    var width by rememberSaveable { mutableIntStateOf(29) }
    var height by rememberSaveable { mutableIntStateOf(29) }
    var widthText by rememberSaveable { mutableStateOf("29") }
    var heightText by rememberSaveable { mutableStateOf("29") }
    var paletteId by rememberSaveable { mutableStateOf(state.settings.defaultPaletteId) }
    var paletteMenu by remember { mutableStateOf(false) }
    val summary = viewModel.paletteCatalog.summaries.firstOrNull { it.id == paletteId }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建空白图纸") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("作品名称") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(29, 58, 87).forEach { size ->
                        FilterChip(
                            selected = width == size && height == size,
                            onClick = {
                                width = size; height = size
                                widthText = size.toString(); heightText = size.toString()
                            },
                            label = { Text("$size") },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = widthText,
                        onValueChange = { raw -> widthText = raw.filter(Char::isDigit).take(3); width = widthText.toIntOrNull() ?: width },
                        label = { Text("宽") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = heightText,
                        onValueChange = { raw -> heightText = raw.filter(Char::isDigit).take(3); height = heightText.toIntOrNull() ?: height },
                        label = { Text("高") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                }
                Box {
                    OutlinedButton(onClick = { paletteMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(summary?.let { "${it.title} · ${it.colorCount} 色" } ?: "选择色卡")
                    }
                    DropdownMenu(expanded = paletteMenu, onDismissRequest = { paletteMenu = false }) {
                        viewModel.paletteCatalog.summaries.forEach { item ->
                            DropdownMenuItem(
                                text = { Text("${item.title} · ${item.colorCount} 色") },
                                onClick = { paletteId = item.id; paletteMenu = false },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(title, width.coerceIn(8, 256), height.coerceIn(8, 256), paletteId) },
                enabled = width in 8..256 && height in 8..256,
            ) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun TrashDialog(
    projects: List<BeadProject>,
    onRestore: (String) -> Unit,
    onEmpty: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("回收站") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                projects.take(8).forEach { project ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(project.title, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = { onRestore(project.id) }) { Text("恢复") }
                    }
                }
                if (projects.size > 8) Text("另有 ${projects.size - 8} 个作品", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        dismissButton = {
            if (projects.isNotEmpty()) TextButton(onClick = { onEmpty(); onDismiss() }) { Text("清空") }
        },
    )
}

private fun statusLabel(status: ProjectStatus): String = when (status) {
    ProjectStatus.DRAFT -> "编辑中"
    ProjectStatus.READY -> "待开拼"
    ProjectStatus.CRAFTING -> "制作中"
    ProjectStatus.COMPLETED -> "已完成"
    ProjectStatus.ARCHIVED -> "已归档"
}

@Composable
private fun statusColor(status: ProjectStatus): Color = when (status) {
    ProjectStatus.COMPLETED -> MaterialTheme.colorScheme.primary
    ProjectStatus.CRAFTING -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun formatDate(timestamp: Long): String =
    SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(timestamp))
