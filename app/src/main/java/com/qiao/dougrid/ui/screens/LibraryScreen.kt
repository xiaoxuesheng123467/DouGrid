@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.qiao.dougrid.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.qiao.dougrid.DouGridUiState
import com.qiao.dougrid.DouGridViewModel
import com.qiao.dougrid.data.BeadProject
import com.qiao.dougrid.data.BeadTemplate
import com.qiao.dougrid.data.DouGridArchiveCodec
import com.qiao.dougrid.data.ProjectStatus
import com.qiao.dougrid.image.ImageFormatSupport
import com.qiao.dougrid.ui.components.PatternThumbnail
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class LibrarySort(val label: String) {
    RECENT("最近更新"),
    NAME("名称"),
    SIZE("尺寸"),
    PROGRESS("制作进度"),
}

@Composable
fun LibraryScreen(
    state: DouGridUiState,
    viewModel: DouGridViewModel,
    onImportUri: (Uri) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTutorial: () -> Unit,
) {
    val context = LocalContext.current
    var search by rememberSaveable { mutableStateOf("") }
    var selectedCategory by rememberSaveable { mutableStateOf<String?>(null) }
    var sortName by rememberSaveable { mutableStateOf(LibrarySort.RECENT.name) }
    var statusFilterName by rememberSaveable { mutableStateOf<String?>(null) }
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    var folderFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var showBlankDialog by rememberSaveable { mutableStateOf(false) }
    var showTrash by rememberSaveable { mutableStateOf(false) }
    var cameraUriString by rememberSaveable { mutableStateOf<String?>(null) }
    var cameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingExportProjectId by rememberSaveable { mutableStateOf<String?>(null) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { selected ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(selected, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            onImportUri(selected)
        }
    }
    val projectArchivePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { selected ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(selected, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            viewModel.importProjectArchive(selected)
        }
    }
    val projectArchiveExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(DouGridArchiveCodec.MIME_TYPE),
    ) { uri ->
        val projectId = pendingExportProjectId
        pendingExportProjectId = null
        if (uri != null && projectId != null) {
            viewModel.exportProjectArchive(projectId, uri)
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val completedUri = cameraUriString?.let(Uri::parse)
        val completedPath = cameraPath
        cameraUriString = null
        cameraPath = null
        if (success) {
            completedUri?.let(onImportUri)
        } else {
            completedPath?.let(::File)?.delete()
        }
    }
    LaunchedEffect(cameraPath) {
        val activePath = cameraPath
        val cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1_000L
        File(context.cacheDir, "images").listFiles().orEmpty()
            .filter { file ->
                file.name.startsWith("dougrid-camera-") &&
                    file.absolutePath != activePath &&
                    file.lastModified() < cutoff
            }
            .forEach(File::delete)
    }
    val query = search.trim()
    val categories = remember(state.templates) { state.templates.map(BeadTemplate::category).distinct() }
    val activeCategory = selectedCategory?.takeIf { it in categories }
    val folders = remember(state.projects) {
        state.projects.mapNotNull(BeadProject::folder).distinct().sortedBy { it.lowercase(Locale.getDefault()) }
    }
    LaunchedEffect(folders, folderFilter) {
        val selected = folderFilter
        if (!selected.isNullOrEmpty() && selected !in folders) folderFilter = null
    }
    val selectedSort = LibrarySort.entries.firstOrNull { it.name == sortName } ?: LibrarySort.RECENT
    val selectedStatus = ProjectStatus.entries.firstOrNull { it.name == statusFilterName }
    val projects by remember(
        state.projects,
        state.editorRevision,
        query,
        selectedSort,
        selectedStatus,
        favoritesOnly,
        folderFilter,
    ) {
        derivedStateOf {
            val filtered = state.projects.filter { project ->
                val matchesQuery = query.isBlank() ||
                    project.title.contains(query, ignoreCase = true) ||
                    project.folder?.contains(query, ignoreCase = true) == true ||
                    project.tags.any { it.contains(query, ignoreCase = true) }
                val matchesFolder = when (folderFilter) {
                    null -> true
                    "" -> project.folder == null
                    else -> project.folder == folderFilter
                }
                matchesQuery &&
                    (selectedStatus == null || project.status == selectedStatus) &&
                    (!favoritesOnly || project.favorite) &&
                    matchesFolder
            }
            when (selectedSort) {
                LibrarySort.RECENT -> filtered.sortedWith(
                    compareByDescending<BeadProject> { it.modifiedAt }
                        .thenBy { it.title.lowercase(Locale.getDefault()) },
                )
                LibrarySort.NAME -> filtered.sortedWith(
                    compareBy<BeadProject> { it.title.lowercase(Locale.getDefault()) }
                        .thenByDescending { it.modifiedAt },
                )
                LibrarySort.SIZE -> filtered.sortedWith(
                    compareByDescending<BeadProject> { it.grid.width * it.grid.height }
                        .thenByDescending { it.modifiedAt },
                )
                LibrarySort.PROGRESS -> filtered.sortedWith(
                    compareByDescending<BeadProject> { it.progress }.thenByDescending { it.modifiedAt },
                )
            }
        }
    }
    val templates by remember(state.templates, activeCategory, query) {
        derivedStateOf {
            state.templates.filter {
                (activeCategory == null || it.category == activeCategory) &&
                    (query.isBlank() || it.title.contains(query, ignoreCase = true) || it.category.contains(query, ignoreCase = true))
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                scrolledContainerColor = MaterialTheme.colorScheme.background,
            ),
            title = {
                Column {
                    Text("乔格", style = MaterialTheme.typography.titleLarge)
                    Text("${state.projects.size} 个作品", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            actions = {
                if (state.deletedProjects.isNotEmpty()) {
                    IconButton(onClick = { showTrash = true }) {
                        Icon(Icons.Default.RestoreFromTrash, contentDescription = "回收站")
                    }
                }
                IconButton(onClick = onOpenTutorial) {
                    Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = "使用教程")
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "设置")
                }
            },
        )

        LazyVerticalGrid(
            columns = GridCells.Adaptive(180.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                    ),
                    label = { Text("搜索作品和模板") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (search.isNotEmpty()) {
                            IconButton(onClick = { search = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "清除搜索")
                            }
                        }
                    },
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                LibraryCreateActions(
                    onImage = {
                        imagePicker.launch(ImageFormatSupport.supportedMimeTypes(Build.VERSION.SDK_INT))
                    },
                    onCamera = {
                        val directory = File(context.cacheDir, "images").apply { mkdirs() }
                        cameraPath?.let(::File)?.delete()
                        var pendingFile: File? = null
                        runCatching {
                            val file = File.createTempFile("dougrid-camera-", ".jpg", directory).also {
                                pendingFile = it
                            }
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                            cameraUriString = uri.toString()
                            cameraPath = file.absolutePath
                            camera.launch(uri)
                        }.onFailure { error ->
                            pendingFile?.delete()
                            cameraUriString = null
                            cameraPath = null
                            viewModel.showMessage(error.message?.let { "无法启动相机：$it" } ?: "无法启动相机")
                        }
                    },
                    onBlank = { showBlankDialog = true },
                    onImportProject = {
                        projectArchivePicker.launch(
                            arrayOf(
                                DouGridArchiveCodec.MIME_TYPE,
                                "application/zip",
                                "application/octet-stream",
                            ),
                        )
                    },
                )
            }

            item(span = { GridItemSpan(maxLineSpan) }) {
                ProjectFilterBar(
                    sort = selectedSort,
                    status = selectedStatus,
                    favoritesOnly = favoritesOnly,
                    folderFilter = folderFilter,
                    folders = folders,
                    onSort = { sortName = it.name },
                    onStatus = { statusFilterName = it?.name },
                    onFavoritesOnly = { favoritesOnly = it },
                    onFolder = { folderFilter = it },
                )
            }

            item(span = { GridItemSpan(maxLineSpan) }) {
                SectionTitle("我的作品", projects.size, state.projects.size)
            }
            if (projects.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyLibraryState(
                        hasFilter = query.isNotBlank() || selectedStatus != null || favoritesOnly || folderFilter != null,
                        onCreate = { showBlankDialog = true },
                    )
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
                        onExport = {
                            pendingExportProjectId = project.id
                            projectArchiveExporter.launch(projectArchiveFileName(project.title))
                        },
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
            onCreate = { title, width, height, paletteId, boardSize ->
                showBlankDialog = false
                viewModel.createBlank(title, width, height, paletteId, boardSize)
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
private fun LibraryCreateActions(
    onImage: () -> Unit,
    onCamera: () -> Unit,
    onBlank: () -> Unit,
    onImportProject: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        if (maxWidth < 520.dp) {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickActionButton(
                        icon = Icons.Default.PhotoLibrary,
                        label = "导入图片",
                        onClick = onImage,
                        modifier = Modifier.weight(1f),
                    )
                    QuickActionButton(
                        icon = Icons.Default.CameraAlt,
                        label = "拍照",
                        onClick = onCamera,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickActionButton(
                        icon = Icons.Default.Add,
                        label = "空白图纸",
                        onClick = onBlank,
                        outlined = true,
                        modifier = Modifier.weight(1f),
                    )
                    QuickActionButton(
                        icon = Icons.Default.FileOpen,
                        label = "导入项目",
                        onClick = onImportProject,
                        outlined = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        } else {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickActionButton(
                    icon = Icons.Default.PhotoLibrary,
                    label = "导入图片",
                    onClick = onImage,
                    modifier = Modifier.weight(1f),
                )
                QuickActionButton(
                    icon = Icons.Default.CameraAlt,
                    label = "拍照",
                    onClick = onCamera,
                    modifier = Modifier.weight(1f),
                )
                QuickActionButton(
                    icon = Icons.Default.Add,
                    label = "空白图纸",
                    onClick = onBlank,
                    outlined = true,
                    modifier = Modifier.weight(1f),
                )
                QuickActionButton(
                    icon = Icons.Default.FileOpen,
                    label = "导入项目",
                    onClick = onImportProject,
                    outlined = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun QuickActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    outlined: Boolean = false,
) {
    if (outlined) {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    } else {
        FilledTonalButton(
            onClick = onClick,
            modifier = modifier,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ProjectFilterBar(
    sort: LibrarySort,
    status: ProjectStatus?,
    favoritesOnly: Boolean,
    folderFilter: String?,
    folders: List<String>,
    onSort: (LibrarySort) -> Unit,
    onStatus: (ProjectStatus?) -> Unit,
    onFavoritesOnly: (Boolean) -> Unit,
    onFolder: (String?) -> Unit,
) {
    var sortMenu by remember { mutableStateOf(false) }
    var statusMenu by remember { mutableStateOf(false) }
    var folderMenu by remember { mutableStateOf(false) }
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item {
            Box {
                OutlinedButton(onClick = { sortMenu = true }) {
                    Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(5.dp))
                    Text(sort.label, maxLines = 1)
                }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    LibrarySort.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            onClick = { sortMenu = false; onSort(option) },
                        )
                    }
                }
            }
        }
        item {
            Box {
                OutlinedButton(onClick = { statusMenu = true }) {
                    Icon(Icons.Default.FilterList, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(5.dp))
                    Text(status?.let(::statusLabel) ?: "全部状态", maxLines = 1)
                }
                DropdownMenu(expanded = statusMenu, onDismissRequest = { statusMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("全部状态") },
                        onClick = { statusMenu = false; onStatus(null) },
                    )
                    ProjectStatus.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(statusLabel(option)) },
                            onClick = { statusMenu = false; onStatus(option) },
                        )
                    }
                }
            }
        }
        item {
            FilterChip(
                selected = favoritesOnly,
                onClick = { onFavoritesOnly(!favoritesOnly) },
                label = { Text("仅收藏") },
                leadingIcon = {
                    Icon(
                        if (favoritesOnly) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
            )
        }
        item {
            Box {
                OutlinedButton(onClick = { folderMenu = true }) {
                    Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(5.dp))
                    Text(
                        when {
                            folderFilter == null -> "全部文件夹"
                            folderFilter.isEmpty() -> "未归类"
                            else -> folderFilter
                        },
                        modifier = Modifier.widthIn(max = 150.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                DropdownMenu(expanded = folderMenu, onDismissRequest = { folderMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("全部文件夹") },
                        onClick = { folderMenu = false; onFolder(null) },
                    )
                    DropdownMenuItem(
                        text = { Text("未归类") },
                        onClick = { folderMenu = false; onFolder("") },
                    )
                    folders.forEach { folder ->
                        DropdownMenuItem(
                            text = { Text(folder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            onClick = { folderMenu = false; onFolder(folder) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, count: Int, total: Int? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Text(
            if (total != null && count != total) "$count / $total" else count.toString(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Card(
        onClick = onOpen,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        PatternThumbnail(
            grid = project.grid,
            palette = palette,
            revision = revision,
            modifier = Modifier.fillMaxWidth().aspectRatio(1.18f),
            boardSize = project.boardSize,
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
                if (project.favorite) {
                    Icon(
                        Icons.Default.Favorite,
                        contentDescription = "已收藏",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.size(3.dp))
                }
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp)) {
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
                            text = { Text("导出项目") },
                            leadingIcon = { Icon(Icons.Default.FileUpload, null) },
                            onClick = { menu = false; onExport() },
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
            if (project.folder != null || project.tags.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (project.folder != null) Icons.Default.Folder else Icons.AutoMirrored.Filled.Label,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        buildString {
                            project.folder?.let { append(it) }
                            project.tags.take(3).forEach { tag ->
                                if (isNotEmpty()) append(" · ")
                                append('#').append(tag)
                            }
                            if (project.tags.size > 3) append(" +${project.tags.size - 3}")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (project.progress > 0f) {
                        "${statusLabel(project.status)} · ${(project.progress * 100).toInt()}%"
                    } else {
                        statusLabel(project.status)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor(project.status),
                )
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
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ),
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
private fun EmptyLibraryState(hasFilter: Boolean, onCreate: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
        Text(if (hasFilter) "没有匹配的作品" else "还没有作品", style = MaterialTheme.typography.titleMedium)
        if (!hasFilter) Button(onClick = onCreate) { Text("新建空白图纸") }
    }
}

@Composable
private fun BlankProjectDialog(
    state: DouGridUiState,
    viewModel: DouGridViewModel,
    onDismiss: () -> Unit,
    onCreate: (String, Int, Int, String, Int) -> Unit,
) {
    var title by rememberSaveable { mutableStateOf("新图纸") }
    var widthText by rememberSaveable { mutableStateOf("29") }
    var heightText by rememberSaveable { mutableStateOf("29") }
    var paletteId by rememberSaveable { mutableStateOf(state.settings.defaultPaletteId) }
    var boardSize by rememberSaveable { mutableIntStateOf(state.settings.defaultBoardSize) }
    var boardSizeText by rememberSaveable { mutableStateOf(state.settings.defaultBoardSize.toString()) }
    var paletteMenu by remember { mutableStateOf(false) }
    val summary = viewModel.paletteCatalog.summaries.firstOrNull { it.id == paletteId }
    val width = widthText.toIntOrNull()
    val height = heightText.toIntOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建空白图纸") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(value = title, onValueChange = { title = it.take(512) }, label = { Text("作品名称") }, singleLine = true)
                Text("图纸尺寸", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(29, 58, 87).forEach { size ->
                        FilterChip(
                            selected = width == size && height == size,
                            onClick = {
                                widthText = size.toString(); heightText = size.toString()
                            },
                            label = { Text("$size") },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = widthText,
                        onValueChange = { raw -> widthText = raw.filter(Char::isDigit).take(3) },
                        label = { Text("宽") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = heightText,
                        onValueChange = { raw -> heightText = raw.filter(Char::isDigit).take(3) },
                        label = { Text("高") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                }
                Text("实体拼板", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf(29, 30, 50).forEach { size ->
                        FilterChip(
                            selected = boardSize == size,
                            onClick = {
                                boardSize = size
                                boardSizeText = size.toString()
                            },
                            label = { Text(size.toString()) },
                        )
                    }
                    OutlinedTextField(
                        value = boardSizeText,
                        onValueChange = { raw ->
                            boardSizeText = raw.filter(Char::isDigit).take(2)
                            boardSize = boardSizeText.toIntOrNull() ?: 0
                        },
                        label = { Text("边长") },
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
                onClick = {
                    onCreate(
                        title,
                        checkNotNull(width).coerceIn(8, 256),
                        checkNotNull(height).coerceIn(8, 256),
                        paletteId,
                        boardSize.coerceIn(BeadProject.MIN_BOARD_SIZE, BeadProject.MAX_BOARD_SIZE),
                    )
                },
                enabled = width != null && width in 8..256 && height != null && height in 8..256 &&
                    boardSize in BeadProject.MIN_BOARD_SIZE..BeadProject.MAX_BOARD_SIZE,
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
    var confirmEmpty by rememberSaveable { mutableStateOf(false) }
    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            title = { Text("清空回收站") },
            text = { Text("${projects.size} 个作品将永久删除，且无法恢复。") },
            confirmButton = {
                Button(
                    onClick = {
                        onEmpty()
                        confirmEmpty = false
                        onDismiss()
                    },
                ) { Text("永久删除") }
            },
            dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text("取消") } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("回收站") },
        text = {
            if (projects.isEmpty()) {
                Text("回收站是空的")
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    lazyItems(projects, key = { it.id }) { project ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(project.title, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            TextButton(onClick = { onRestore(project.id) }) { Text("恢复") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        dismissButton = {
            if (projects.isNotEmpty()) TextButton(onClick = { confirmEmpty = true }) { Text("清空") }
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

private fun projectArchiveFileName(title: String): String {
    val invalid = setOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')
    val base = title.trim()
        .map { character -> if (character.isISOControl() || character in invalid) '_' else character }
        .joinToString("")
        .trim('.', ' ')
        .take(80)
        .ifBlank { "乔格作品" }
    return "$base.${DouGridArchiveCodec.FILE_EXTENSION}"
}
