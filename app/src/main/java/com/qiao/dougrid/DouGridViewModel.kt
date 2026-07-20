package com.qiao.dougrid

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qiao.dougrid.core.BeadPalette
import com.qiao.dougrid.core.CellDelta
import com.qiao.dougrid.core.ColorMath
import com.qiao.dougrid.core.ConversionMode
import com.qiao.dougrid.core.EMPTY_CELL
import com.qiao.dougrid.core.EditorHistory
import com.qiao.dougrid.core.GridRegion
import com.qiao.dougrid.core.GridSelection
import com.qiao.dougrid.core.PatternGrid
import com.qiao.dougrid.data.AppSettings
import com.qiao.dougrid.data.BeadProject
import com.qiao.dougrid.data.BeadTemplate
import com.qiao.dougrid.data.DouGridArchiveCodec
import com.qiao.dougrid.data.EditorTool
import com.qiao.dougrid.data.InventoryEntry
import com.qiao.dougrid.data.InventoryCsvCodec
import com.qiao.dougrid.data.InventoryImportMode
import com.qiao.dougrid.data.MainDestination
import com.qiao.dougrid.data.PaletteCatalog
import com.qiao.dougrid.data.PersistedAppState
import com.qiao.dougrid.data.ProjectRepository
import com.qiao.dougrid.data.ProjectStatus
import com.qiao.dougrid.data.TemplateCatalog
import com.qiao.dougrid.image.BitmapPatternConverter
import com.qiao.dougrid.image.ImageImportOptions
import com.qiao.dougrid.export.MaterialPlanner
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.UUID

data class DouGridUiState(
    val isLoading: Boolean = true,
    val isProcessingImage: Boolean = false,
    val projects: List<BeadProject> = emptyList(),
    val deletedProjects: List<BeadProject> = emptyList(),
    val inventory: List<InventoryEntry> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val templates: List<BeadTemplate> = emptyList(),
    val activeCraftProjectId: String? = null,
    val editorRevision: Long = 0,
    val editorStatsRevision: Long = 0,
    val selectedEditorColor: Int = 0,
    val editorTool: EditorTool = EditorTool.PENCIL,
    val editorSelection: GridRegion? = null,
    val openProjectRequestId: String? = null,
    val materialSummaryRequestProjectId: String? = null,
    val mainDestination: MainDestination = MainDestination.LIBRARY,
    val showTutorial: Boolean = false,
    val message: String? = null,
    val persistenceWarning: String? = null,
    val isReadOnlyRecovery: Boolean = false,
)

class DouGridViewModel(application: Application) : AndroidViewModel(application) {
    private data class ActiveStroke(
        val projectId: String,
        val replacement: Int,
        val before: LinkedHashMap<Int, Int> = linkedMapOf(),
        val completedBefore: LinkedHashMap<Int, Byte> = linkedMapOf(),
    )

    private data class SelectionClipboard(
        val paletteId: String,
        val selection: GridSelection,
    )

    private data class PaletteRemapResult(
        val grid: PatternGrid,
        val exactPaletteMatch: Boolean,
    )

    val paletteCatalog = PaletteCatalog(application)
    private val repository = ProjectRepository(application)
    private val histories = mutableMapOf<String, EditorHistory>()
    private var saveJob: Job? = null
    private val saveMutex = Mutex()
    private val persistenceTransactionMutex = Mutex()
    private var saveEpoch: Long = 0L
    private var persistenceReadOnly = false
    private var persistenceTransactionActive = false
    private var persistentPersistenceWarning: String? = null
    private var imageImportJob: Job? = null
    private var imageImportGeneration: Long = 0
    private var activeStroke: ActiveStroke? = null
    private var selectionClipboard: SelectionClipboard? = null

    private val _uiState = MutableStateFlow(DouGridUiState())
    val uiState: StateFlow<DouGridUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val loaded = try {
                repository.load(
                    validateProject = projectValidator@{ candidate ->
                        val installedPalette = paletteCatalog.get(candidate.paletteId)
                        if (installedPalette == null) {
                            "工程引用了当前版本不存在的色卡：${candidate.paletteId}"
                        } else {
                            val invalidIndex = candidate.grid.cells.firstOrNull { colorIndex ->
                                colorIndex != EMPTY_CELL && colorIndex !in installedPalette.colors.indices
                            }
                            invalidIndex?.let { "工程网格含无效色号索引：$it" }
                        }
                    },
                    validateInventory = inventoryValidator@{ entry ->
                        val installedPalette = paletteCatalog.get(entry.paletteId)
                        if (installedPalette == null) {
                            "库存引用了当前版本不存在的色卡：${entry.paletteId}"
                        } else if (installedPalette.colors.none { it.code == entry.colorCode }) {
                            "库存引用了色卡中不存在的色号：${entry.paletteId}/${entry.colorCode}"
                        } else {
                            null
                        }
                    },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val defaultPalette = paletteCatalog.default()
                val templates = TemplateCatalog.builtIns(defaultPalette)
                val recoveryProject = TemplateCatalog.instantiate(templates.first())
                persistenceReadOnly = true
                persistentPersistenceWarning = "本地数据读取失败，当前为临时恢复模式；编辑不会保存，原文件未被覆盖。"
                _uiState.value = DouGridUiState(
                    isLoading = false,
                    projects = listOf(recoveryProject),
                    settings = AppSettings(defaultPaletteId = defaultPalette.id),
                    templates = templates,
                    activeCraftProjectId = recoveryProject.id,
                    selectedEditorColor = firstUsedColor(recoveryProject) ?: 0,
                    message = error.message ?: "本地工程读取失败，原文件已保护且不会被覆盖",
                    persistenceWarning = persistentPersistenceWarning,
                    isReadOnlyRecovery = true,
                )
                return@launch
            }
            persistenceReadOnly = false
            persistentPersistenceWarning = repository.loadIssues.takeIf { it.isNotEmpty() }?.let {
                "有 ${it.size} 条本地记录无法读取，原始数据已保留；请先导出或修复后再继续编辑。"
            }
            val defaultPalette = paletteCatalog.get(loaded.settings.defaultPaletteId) ?: paletteCatalog.default()
            val templates = TemplateCatalog.builtIns(defaultPalette)
            val projects = loaded.projects.ifEmpty {
                listOf(
                    TemplateCatalog.instantiate(templates.first()).copy(
                        boardSize = loaded.settings.defaultBoardSize,
                    ),
                )
            }
            _uiState.value = DouGridUiState(
                isLoading = false,
                projects = projects,
                deletedProjects = loaded.deletedProjects,
                inventory = loaded.inventory,
                settings = loaded.settings.copy(defaultPaletteId = defaultPalette.id),
                templates = templates,
                activeCraftProjectId = projects.firstOrNull()?.id,
                selectedEditorColor = firstUsedColor(projects.firstOrNull()) ?: 0,
                showTutorial = !loaded.settings.hasSeenTutorial,
                message = repository.loadIssues.takeIf { it.isNotEmpty() }?.let {
                    "有 ${it.size} 条本地记录暂时无法读取，原始数据已保留"
                },
                persistenceWarning = persistentPersistenceWarning,
                isReadOnlyRecovery = false,
            )
            if (loaded.projects.isEmpty()) scheduleSave(immediate = true)
            repository.deleteOrphanedReferences(
                (projects + loaded.deletedProjects).mapNotNull { it.sourcePath },
            )
        }
    }

    fun palette(id: String): BeadPalette = paletteCatalog.get(id) ?: paletteCatalog.default()

    fun inventoryPaletteIndices(paletteId: String): IntArray? =
        availableInventoryIndices(palette(paletteId))

    fun inventoryPaletteCapacities(paletteId: String): IntArray =
        inventoryCapacities(palette(paletteId))

    fun project(id: String?): BeadProject? = _uiState.value.projects.firstOrNull { it.id == id }

    fun setEditorTool(tool: EditorTool) {
        _uiState.value = _uiState.value.copy(editorTool = tool)
    }

    fun setEditorSelection(projectId: String, region: GridRegion?) {
        val project = project(projectId) ?: return
        val clamped = region?.clampedTo(project.grid.width, project.grid.height)
        _uiState.value = _uiState.value.copy(editorSelection = clamped)
    }

    fun selectEditorColor(index: Int) {
        _uiState.value = _uiState.value.copy(selectedEditorColor = index, editorTool = EditorTool.PENCIL)
    }

    fun requestOpenProject(id: String) {
        val project = project(id) ?: return
        _uiState.value = _uiState.value.copy(
            openProjectRequestId = id,
            selectedEditorColor = firstUsedColor(project) ?: 0,
            editorSelection = null,
        )
    }

    fun consumeOpenProjectRequest() {
        _uiState.value = _uiState.value.copy(openProjectRequestId = null)
    }

    fun consumeMaterialSummaryRequest(projectId: String) {
        if (_uiState.value.materialSummaryRequestProjectId == projectId) {
            _uiState.value = _uiState.value.copy(materialSummaryRequestProjectId = null)
        }
    }

    fun selectMainDestination(destination: MainDestination) {
        _uiState.value = _uiState.value.copy(mainDestination = destination)
    }

    fun openTutorial() {
        _uiState.value = _uiState.value.copy(showTutorial = true)
    }

    fun completeTutorial() {
        if (!writableOrWarn()) return
        val state = _uiState.value
        _uiState.value = state.copy(
            showTutorial = false,
            settings = state.settings.copy(hasSeenTutorial = true),
        )
        scheduleSave(immediate = true)
    }

    fun createBlank(
        title: String,
        width: Int,
        height: Int,
        paletteId: String,
        boardSize: Int = _uiState.value.settings.defaultBoardSize,
    ) {
        if (!writableOrWarn()) return
        val targetPalette = palette(paletteId)
        val newProject = BeadProject(
            title = normalizeMetadata(title, 512).ifBlank { "未命名作品" },
            paletteId = targetPalette.id,
            grid = PatternGrid(width.coerceIn(8, 256), height.coerceIn(8, 256)),
            sourceMode = ConversionMode.SPRITE,
            boardSize = boardSize.coerceIn(BeadProject.MIN_BOARD_SIZE, BeadProject.MAX_BOARD_SIZE),
        )
        viewModelScope.launch {
            try {
                addAndPersistAndOpen(newProject)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showMessage(error.message ?: "新建工程保存失败")
            }
        }
    }

    fun createFromTemplate(templateId: String) {
        if (!writableOrWarn()) return
        val template = _uiState.value.templates.firstOrNull { it.id == templateId } ?: return
        val project = TemplateCatalog.instantiate(template).copy(
            boardSize = _uiState.value.settings.defaultBoardSize,
        )
        viewModelScope.launch {
            try {
                addAndPersistAndOpen(project)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showMessage(error.message ?: "模板工程保存失败")
            }
        }
    }

    fun importImage(
        uri: Uri,
        title: String,
        paletteId: String,
        options: ImageImportOptions,
    ) {
        if (!writableOrWarn()) return
        if (_uiState.value.isProcessingImage) return
        val generation = ++imageImportGeneration
        _uiState.value = _uiState.value.copy(isProcessingImage = true, message = null)
        imageImportJob = viewModelScope.launch {
            try {
                val targetPalette = palette(paletteId)
                val capacities = options.inventoryMode?.let { inventoryCapacities(targetPalette) }
                if (capacities != null && capacities.sumOf(Int::toLong) == 0L) {
                    error("豆仓里还没有当前色卡的库存")
                }
                BitmapPatternConverter.prepareImport(
                    context = getApplication(),
                    uri = uri,
                    palette = targetPalette,
                    options = options,
                    paletteCapacities = capacities,
                ).use { prepared ->
                    currentCoroutineContext().ensureActive()
                    if (generation != imageImportGeneration) return@use
                    val draft = BeadProject(
                        title = normalizeMetadata(title, 512).ifBlank { "图片图纸" },
                        paletteId = targetPalette.id,
                        grid = prepared.grid,
                        sourceMode = options.mode,
                        status = ProjectStatus.READY,
                        boardSize = _uiState.value.settings.defaultBoardSize,
                    )
                    var keepReference = false
                    try {
                        val sourcePath = repository.saveReference(prepared.referenceBitmap, draft.id)
                        currentCoroutineContext().ensureActive()
                        if (generation != imageImportGeneration) return@use
                        addAndPersistAndOpen(
                            project = draft.copy(sourcePath = sourcePath),
                            showMaterialSummary = true,
                        )
                        keepReference = true
                    } finally {
                        if (!keepReference) {
                            withContext(NonCancellable) { repository.deleteProjectReferences(draft.id) }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == imageImportGeneration) {
                    _uiState.value = _uiState.value.copy(
                        message = error.message ?: "图片处理失败",
                    )
                }
            } finally {
                if (generation == imageImportGeneration) {
                    imageImportJob = null
                    _uiState.value = _uiState.value.copy(isProcessingImage = false)
                }
            }
        }
    }

    fun cancelImageImport() {
        imageImportGeneration++
        imageImportJob?.cancel()
        imageImportJob = null
        if (_uiState.value.isProcessingImage) {
            _uiState.value = _uiState.value.copy(isProcessingImage = false)
        }
    }

    fun applyStroke(projectId: String, indices: Collection<Int>, colorIndex: Int? = null) {
        if (!writableOrWarn()) return
        beginEditorStroke(projectId, colorIndex)
        extendEditorStroke(projectId, indices)
        endEditorStroke(projectId)
    }

    fun beginEditorStroke(projectId: String, colorIndex: Int? = null) {
        if (!writableOrWarn()) return
        val project = project(projectId) ?: return
        val replacement = colorIndex ?: when (_uiState.value.editorTool) {
            EditorTool.ERASER -> EMPTY_CELL
            else -> _uiState.value.selectedEditorColor
        }
        activeStroke = ActiveStroke(project.id, replacement)
    }

    fun extendEditorStroke(projectId: String, indices: Collection<Int>) {
        if (!writableOrWarn()) return
        var session = activeStroke
        if (session?.projectId != projectId) {
            beginEditorStroke(projectId)
            session = activeStroke
        }
        session ?: return
        val project = project(projectId) ?: return
        val grid = project.grid
        var changed = false
        for (index in indices) {
            if (index !in grid.cells.indices) continue
            if (grid.cells[index] != session.replacement) {
                session.before.putIfAbsent(index, grid.cells[index])
                session.completedBefore.putIfAbsent(index, grid.completed[index])
                grid.cells[index] = session.replacement
                grid.completed[index] = 0
                changed = true
            }
        }
        if (changed) {
            val state = _uiState.value
            _uiState.value = state.copy(editorRevision = state.editorRevision + 1)
        }
    }

    fun endEditorStroke(projectId: String) {
        if (!writableOrWarn()) return
        val session = activeStroke?.takeIf { it.projectId == projectId } ?: return
        activeStroke = null
        if (session.before.isEmpty()) return
        val grid = project(projectId)?.grid ?: return
        val changed = session.before.keys.toList()
        val delta = CellDelta(
            indices = changed.toIntArray(),
            before = IntArray(changed.size) { session.before.getValue(changed[it]) },
            after = IntArray(changed.size) { grid.cells[changed[it]] },
            label = if (session.replacement == EMPTY_CELL) "橡皮" else "画笔",
            completedBefore = ByteArray(changed.size) { session.completedBefore.getValue(changed[it]) },
            completedAfter = ByteArray(changed.size),
        )
        history(projectId).record(delta)
        touchProject(projectId)
    }

    fun applyToolAt(projectId: String, index: Int) {
        if (!writableOrWarn()) return
        val project = project(projectId) ?: return
        if (index !in project.grid.cells.indices) return
        when (_uiState.value.editorTool) {
            EditorTool.PENCIL -> applyStroke(projectId, listOf(index))
            EditorTool.ERASER -> applyStroke(projectId, listOf(index), EMPTY_CELL)
            EditorTool.FILL -> {
                val x = index % project.grid.width
                val y = index / project.grid.width
                val delta = project.grid.floodFill(x, y, _uiState.value.selectedEditorColor)
                recordEditorDelta(projectId, delta)
            }
            EditorTool.PICKER -> {
                val selected = project.grid.cells[index]
                if (selected != EMPTY_CELL) {
                    _uiState.value = _uiState.value.copy(
                        selectedEditorColor = selected,
                        editorTool = EditorTool.PENCIL,
                    )
                }
            }
            EditorTool.REPLACE -> {
                val target = project.grid.cells[index]
                if (target != EMPTY_CELL) {
                    val delta = project.grid.replaceAll(target, _uiState.value.selectedEditorColor)
                    recordEditorDelta(projectId, delta)
                }
            }
            EditorTool.SELECT -> Unit
            EditorTool.PAN -> Unit
        }
    }

    fun mirror(projectId: String, horizontal: Boolean) {
        if (!writableOrWarn()) return
        val project = project(projectId) ?: return
        val delta = if (horizontal) project.grid.mirrorHorizontal() else project.grid.mirrorVertical()
        recordEditorDelta(projectId, delta)
    }

    fun copyEditorSelection(projectId: String) {
        val project = project(projectId) ?: return
        val region = _uiState.value.editorSelection ?: return
        selectionClipboard = SelectionClipboard(project.paletteId, project.grid.copyRegion(region))
        showMessage("已复制 ${region.width} × ${region.height} 选区")
    }

    fun clearEditorSelection(projectId: String) {
        if (!writableOrWarn()) return
        val project = project(projectId) ?: return
        val region = _uiState.value.editorSelection ?: return
        recordEditorDelta(projectId, project.grid.clearRegion(region))
    }

    fun pasteEditorSelection(projectId: String) {
        if (!writableOrWarn()) return
        val project = project(projectId) ?: return
        val clipboard = selectionClipboard ?: return showMessage("还没有复制选区")
        if (clipboard.paletteId != project.paletteId) {
            return showMessage("不同色卡的选区不能直接粘贴")
        }
        val anchor = _uiState.value.editorSelection
        val left = anchor?.left ?: 0
        val top = anchor?.top ?: 0
        recordEditorDelta(projectId, project.grid.pasteRegion(clipboard.selection, left, top))
        _uiState.value = _uiState.value.copy(
            editorSelection = GridRegion(left, top, clipboard.selection.width, clipboard.selection.height)
                .clampedTo(project.grid.width, project.grid.height),
        )
    }

    fun moveEditorSelection(projectId: String, deltaColumn: Int, deltaRow: Int) {
        if (!writableOrWarn()) return
        val project = project(projectId) ?: return
        val region = _uiState.value.editorSelection ?: return
        val left = (region.left + deltaColumn).coerceIn(0, project.grid.width - region.width)
        val top = (region.top + deltaRow).coerceIn(0, project.grid.height - region.height)
        if (left == region.left && top == region.top) return
        recordEditorDelta(projectId, project.grid.moveRegion(region, left, top))
        _uiState.value = _uiState.value.copy(editorSelection = region.copy(left = left, top = top))
    }

    fun rotateEditorSelection(projectId: String, clockwise: Boolean) {
        if (!writableOrWarn()) return
        val project = project(projectId) ?: return
        val region = _uiState.value.editorSelection ?: return
        val rotatedWidth = region.height
        val rotatedHeight = region.width
        if (rotatedWidth > project.grid.width || rotatedHeight > project.grid.height) {
            return showMessage("当前选区旋转后超出画布")
        }
        val destinationLeft = region.left.coerceIn(0, project.grid.width - rotatedWidth)
        val destinationTop = region.top.coerceIn(0, project.grid.height - rotatedHeight)
        val delta = if (clockwise) {
            project.grid.rotateRegionClockwise(region)
        } else {
            project.grid.rotateRegionCounterClockwise(region)
        }
        recordEditorDelta(projectId, delta)
        _uiState.value = _uiState.value.copy(
            editorSelection = GridRegion(destinationLeft, destinationTop, rotatedWidth, rotatedHeight),
        )
    }

    fun mirrorEditorSelection(projectId: String, horizontal: Boolean) {
        if (!writableOrWarn()) return
        val project = project(projectId) ?: return
        val region = _uiState.value.editorSelection ?: return
        val delta = if (horizontal) {
            project.grid.mirrorRegionHorizontal(region)
        } else {
            project.grid.mirrorRegionVertical(region)
        }
        recordEditorDelta(projectId, delta)
    }

    fun substituteProjectColor(projectId: String, sourceColorIndex: Int, replacementColorIndex: Int) {
        if (!writableOrWarn()) return
        val project = project(projectId) ?: return
        val delta = project.grid.replaceAll(sourceColorIndex, replacementColorIndex) ?: return
        recordEditorDelta(projectId, delta)
        selectEditorColor(replacementColorIndex)
        showMessage("已替换颜色，可使用撤销恢复")
    }

    fun undo(projectId: String) {
        if (!writableOrWarn()) return
        val project = project(projectId) ?: return
        if (history(projectId).undo(project.grid) != null) touchProject(projectId)
    }

    fun redo(projectId: String) {
        if (!writableOrWarn()) return
        val project = project(projectId) ?: return
        if (history(projectId).redo(project.grid) != null) touchProject(projectId)
    }

    fun canUndo(projectId: String): Boolean = histories[projectId]?.canUndo == true

    fun canRedo(projectId: String): Boolean = histories[projectId]?.canRedo == true

    private fun recordEditorDelta(projectId: String, delta: CellDelta?) {
        if (delta == null || delta.indices.isEmpty()) return
        val grid = project(projectId)?.grid ?: return
        val completedBefore = ByteArray(delta.indices.size) { offset ->
            grid.completed[delta.indices[offset]]
        }
        val completedAfter = ByteArray(delta.indices.size)
        delta.indices.forEach { index -> grid.completed[index] = 0 }
        history(projectId).record(
            delta.copy(completedBefore = completedBefore, completedAfter = completedAfter),
        )
        touchProject(projectId)
    }

    fun renameProject(projectId: String, title: String) {
        if (!writableOrWarn()) return
        updateProject(projectId) { it.copy(title = normalizeMetadata(title, 512).ifBlank { it.title }, modifiedAt = now()) }
    }

    fun toggleFavorite(projectId: String) {
        if (!writableOrWarn()) return
        updateProject(projectId) { it.copy(favorite = !it.favorite, modifiedAt = now()) }
    }

    fun duplicateProject(projectId: String) {
        if (!writableOrWarn()) return
        val source = project(projectId) ?: return
        val duplicate = source.copy(
            id = java.util.UUID.randomUUID().toString(),
            title = "${source.title} 副本".take(512),
            grid = source.grid.deepCopy().also { it.completed.fill(0) },
            createdAt = now(),
            modifiedAt = now(),
            status = ProjectStatus.DRAFT,
            inventoryDeducted = false,
            sourcePath = null,
            craftElapsedSeconds = 0L,
            lastCraftBoardIndex = 0,
        )
        viewModelScope.launch {
            var keepReference = false
            try {
                val copiedReference = source.sourcePath?.let { path ->
                    repository.copyReference(path, duplicate.id)
                }
                currentCoroutineContext().ensureActive()
                addAndPersistAndOpen(duplicate.copy(sourcePath = copiedReference))
                keepReference = true
            } finally {
                if (!keepReference) {
                    withContext(NonCancellable) { repository.deleteProjectReferences(duplicate.id) }
                }
            }
        }
    }

    fun deleteProject(projectId: String) {
        if (!writableOrWarn()) return
        val state = _uiState.value
        val target = state.projects.firstOrNull { it.id == projectId } ?: return
        val remaining = state.projects.filterNot { it.id == projectId }
        val trashCandidates = listOf(target) + state.deletedProjects
        val retainedTrash = trashCandidates.take(30)
        val retainedSourcePaths = (remaining + retainedTrash).mapNotNullTo(hashSetOf()) { it.sourcePath }
        val evictedIds = trashCandidates.drop(30)
            .filter { it.sourcePath == null || it.sourcePath !in retainedSourcePaths }
            .map { it.id }
        _uiState.value = state.copy(
            projects = remaining,
            deletedProjects = retainedTrash,
            activeCraftProjectId = state.activeCraftProjectId.takeUnless { it == projectId }
                ?: remaining.firstOrNull()?.id,
            editorRevision = state.editorRevision + 1,
            editorStatsRevision = state.editorStatsRevision + 1,
        )
        histories.remove(projectId)
        scheduleSave()
        if (evictedIds.isNotEmpty()) {
            viewModelScope.launch { repository.deleteProjectReferences(evictedIds) }
        }
    }

    fun restoreProject(projectId: String) {
        if (!writableOrWarn()) return
        val state = _uiState.value
        val target = state.deletedProjects.firstOrNull { it.id == projectId } ?: return
        _uiState.value = state.copy(
            projects = listOf(target.copy(modifiedAt = now())) + state.projects,
            deletedProjects = state.deletedProjects.filterNot { it.id == projectId },
        )
        scheduleSave()
    }

    fun emptyTrash() {
        if (!writableOrWarn()) return
        val state = _uiState.value
        val retainedSourcePaths = state.projects.mapNotNullTo(hashSetOf()) { it.sourcePath }
        val removableIds = state.deletedProjects
            .filter { it.sourcePath == null || it.sourcePath !in retainedSourcePaths }
            .map { it.id }
        _uiState.value = state.copy(deletedProjects = emptyList())
        scheduleSave()
        if (removableIds.isNotEmpty()) {
            viewModelScope.launch { repository.deleteProjectReferences(removableIds) }
        }
    }

    fun selectCraftProject(projectId: String) {
        if (project(projectId) != null) _uiState.value = _uiState.value.copy(activeCraftProjectId = projectId)
    }

    fun selectCraftBoard(projectId: String, boardIndex: Int) {
        if (!writableOrWarn()) return
        val target = project(projectId) ?: return
        val safeIndex = boardIndex.coerceIn(0, (target.boardCount - 1).coerceAtLeast(0))
        if (target.lastCraftBoardIndex == safeIndex) return
        updateProjectStateOnly(projectId, saveDelay = 250) { it.copy(lastCraftBoardIndex = safeIndex) }
    }

    fun recordCraftTime(projectId: String, elapsedSeconds: Long) {
        if (!writableOrWarn()) return
        val target = project(projectId) ?: return
        val safeElapsed = elapsedSeconds.coerceAtLeast(target.craftElapsedSeconds)
        if (safeElapsed == target.craftElapsedSeconds) return
        updateProjectStateOnly(projectId, saveDelay = 250) { it.copy(craftElapsedSeconds = safeElapsed) }
    }

    fun completeCraftBoard(projectId: String, boardIndex: Int, colorIndex: Int?) {
        if (!writableOrWarn()) return
        val target = project(projectId) ?: return
        if (boardIndex !in 0 until target.boardCount) return
        val boardColumn = boardIndex % target.boardColumns
        val boardRow = boardIndex / target.boardColumns
        val startColumn = boardColumn * target.boardSize
        val startRow = boardRow * target.boardSize
        val endColumn = minOf(startColumn + target.boardSize, target.grid.width)
        val endRow = minOf(startRow + target.boardSize, target.grid.height)
        var changed = false
        for (row in startRow until endRow) {
            for (column in startColumn until endColumn) {
                val index = target.grid.indexOf(column, row)
                val cellColor = target.grid.cells[index]
                if (cellColor == EMPTY_CELL || colorIndex != null && cellColor != colorIndex) continue
                if (target.grid.completed[index].toInt() == 0) {
                    target.grid.completed[index] = 1
                    changed = true
                }
            }
        }
        if (!changed) return
        updateProject(projectId, saveDelay = 180) {
            it.copy(status = ProjectStatus.CRAFTING, modifiedAt = now(), lastCraftBoardIndex = boardIndex)
        }
    }

    fun toggleCraftCell(projectId: String, index: Int) {
        if (!writableOrWarn()) return
        val project = project(projectId) ?: return
        if (!project.grid.toggleCompleted(index)) {
            if (index !in project.grid.cells.indices || project.grid.cells[index] == EMPTY_CELL) return
        }
        updateProject(projectId, saveDelay = 180) {
            it.copy(
                status = if (it.status == ProjectStatus.COMPLETED) ProjectStatus.COMPLETED else ProjectStatus.CRAFTING,
                modifiedAt = now(),
            )
        }
    }

    fun resetCraftProgress(projectId: String) {
        if (!writableOrWarn()) return
        val project = project(projectId) ?: return
        project.grid.completed.fill(0)
        updateProject(projectId) { it.copy(status = ProjectStatus.READY, modifiedAt = now()) }
    }

    fun completeProject(projectId: String, deductInventory: Boolean) {
        if (!writableOrWarn()) return
        val target = project(projectId) ?: return
        var inventory = _uiState.value.inventory
        var deducted = target.inventoryDeducted
        if (deductInventory && !deducted) {
            val targetPalette = palette(target.paletteId)
            val counts = target.grid.colorCounts()
            val mutable = inventory.associateBy { it.key }.toMutableMap()
            counts.forEach { (colorIndex, count) ->
                val color = targetPalette.colors.getOrNull(colorIndex) ?: return@forEach
                val key = "${target.paletteId}::${color.code}"
                val current = mutable[key] ?: InventoryEntry(target.paletteId, color.code, 0)
                mutable[key] = current.copy(onHand = (current.onHand - count).coerceAtLeast(0))
            }
            inventory = mutable.values.sortedWith(compareBy(InventoryEntry::paletteId, InventoryEntry::colorCode))
            deducted = true
        }
        val state = _uiState.value
        _uiState.value = state.copy(inventory = inventory)
        updateProject(projectId) {
            it.grid.completed.indices.forEach { index ->
                if (it.grid.cells[index] != EMPTY_CELL) it.grid.completed[index] = 1
            }
            it.copy(
                status = ProjectStatus.COMPLETED,
                inventoryDeducted = deducted,
                modifiedAt = now(),
            )
        }
    }

    fun updateInventory(paletteId: String, colorCode: String, amount: Int, bagSize: Int? = null) {
        if (!writableOrWarn()) return
        val state = _uiState.value
        val key = "$paletteId::$colorCode"
        val mutable = state.inventory.associateBy { it.key }.toMutableMap()
        val current = mutable[key] ?: InventoryEntry(paletteId, colorCode, 0)
        val updated = current.copy(
            onHand = amount.coerceIn(0, 999_999),
            bagSize = bagSize?.coerceIn(1, 999_999) ?: current.bagSize.coerceIn(1, 999_999),
        )
        if (updated.onHand == 0 && bagSize == null) mutable.remove(key) else mutable[key] = updated
        _uiState.value = state.copy(
            inventory = mutable.values.sortedWith(compareBy(InventoryEntry::paletteId, InventoryEntry::colorCode)),
        )
        scheduleSave()
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        if (!writableOrWarn()) return
        val updated = transform(_uiState.value.settings)
        val defaultPalette = palette(updated.defaultPaletteId)
        _uiState.value = _uiState.value.copy(
            settings = updated.copy(
                defaultPaletteId = defaultPalette.id,
                defaultBoardSize = updated.defaultBoardSize.coerceIn(
                    BeadProject.MIN_BOARD_SIZE,
                    BeadProject.MAX_BOARD_SIZE,
                ),
                lowStockThreshold = updated.lowStockThreshold.coerceIn(0, 999_999),
            ),
            templates = TemplateCatalog.builtIns(defaultPalette),
        )
        scheduleSave()
    }

    fun updateProjectMetadata(
        projectId: String,
        title: String,
        folder: String?,
        tags: List<String>,
        boardSize: Int,
    ) {
        if (!writableOrWarn()) return
        val normalizedTags = tags
            .asSequence()
            .map { normalizeMetadata(it, 40) }
            .filter(String::isNotEmpty)
            .distinctBy(String::lowercase)
            .take(12)
            .toList()
        updateProject(projectId) {
            it.copy(
                title = normalizeMetadata(title, 512).ifBlank { it.title },
                folder = folder?.let { value -> normalizeMetadata(value, 120).takeIf(String::isNotEmpty) },
                tags = normalizedTags,
                boardSize = boardSize.coerceIn(BeadProject.MIN_BOARD_SIZE, BeadProject.MAX_BOARD_SIZE),
                lastCraftBoardIndex = it.lastCraftBoardIndex.coerceIn(
                    0,
                    (it.copy(boardSize = boardSize.coerceIn(BeadProject.MIN_BOARD_SIZE, BeadProject.MAX_BOARD_SIZE)).boardCount - 1)
                        .coerceAtLeast(0),
                ),
                modifiedAt = now(),
            )
        }
    }

    fun exportProjectArchive(projectId: String, uri: Uri) {
        val target = project(projectId)?.let { it.copy(grid = it.grid.deepCopy()) } ?: return
        val targetPalette = palette(target.paletteId)
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val reference = target.sourcePath?.let { path -> referencePngForArchive(File(path)) }
                    getApplication<Application>().contentResolver.openOutputStream(uri)?.use { output ->
                        DouGridArchiveCodec.write(output, target, targetPalette, reference)
                    } ?: error("无法写入项目包")
                }
            }.onSuccess { showMessage("项目包已导出") }
                .onFailure { showMessage(it.message ?: "项目包导出失败") }
        }
    }

    fun importProjectArchive(uri: Uri) {
        if (!writableOrWarn()) return
        viewModelScope.launch {
            var importedProjectId: String? = null
            try {
                val archive = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use(DouGridArchiveCodec::read)
                        ?: error("无法读取项目包")
                }
                val archivedPaletteInstalled = paletteCatalog.get(archive.paletteSnapshot.id)
                val installedPalette = archivedPaletteInstalled ?: paletteCatalog.default()
                val remap = remapArchivedGrid(
                    archive.project.grid,
                    archive.paletteSnapshot,
                    installedPalette,
                )
                val newId = UUID.randomUUID().toString()
                importedProjectId = newId
                val referencePath = archive.referencePng?.let { bytes ->
                    repository.saveReferencePng(bytes, newId)
                }
                val imported = archive.project.copy(
                    id = newId,
                    paletteId = installedPalette.id,
                    grid = remap.grid,
                    sourcePath = referencePath,
                    createdAt = archive.project.createdAt,
                    modifiedAt = now(),
                    inventoryDeducted = false,
                    lastCraftBoardIndex = archive.project.lastCraftBoardIndex.coerceIn(
                        0,
                        (archive.project.boardCount - 1).coerceAtLeast(0),
                    ),
                )
                val successMessage = if (archivedPaletteInstalled == null) {
                        "已导入 ${imported.title}，原色卡不可用，已就近映射到 ${installedPalette.title}"
                    } else if (!remap.exactPaletteMatch) {
                        "已导入 ${imported.title}；色卡版本不同，已按本机色号与近色重新映射"
                    } else {
                        "已导入 ${imported.title}"
                    }
                withPersistenceTransaction {
                    val previousState = _uiState.value
                    val stagedState = previousState.copy(
                        projects = listOf(imported) + previousState.projects,
                        activeCraftProjectId = imported.id,
                        selectedEditorColor = firstUsedColor(imported) ?: 0,
                        editorRevision = previousState.editorRevision + 1,
                        editorStatsRevision = previousState.editorStatsRevision + 1,
                    )
                    _uiState.value = stagedState
                    try {
                        persistStateNowLocked(stagedState)
                    } catch (error: Throwable) {
                        invalidatePendingSaves()
                        rollbackAddedProject(imported.id, previousState)
                        throw error
                    }
                    _uiState.value = _uiState.value.copy(
                        openProjectRequestId = imported.id,
                        message = successMessage,
                    )
                    importedProjectId = null
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showMessage(error.message ?: "项目包导入失败")
            } finally {
                importedProjectId?.let { repository.deleteProjectReferences(it) }
            }
        }
    }

    fun exportInventoryCsv(uri: Uri) {
        val inventory = _uiState.value.inventory.toList()
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri)?.use { output ->
                        InventoryCsvCodec.write(output, inventory)
                    } ?: error("无法写入豆仓 CSV")
                }
            }.onSuccess { showMessage("豆仓 CSV 已导出") }
                .onFailure { showMessage(it.message ?: "豆仓导出失败") }
        }
    }

    fun importInventoryCsv(uri: Uri, replace: Boolean) {
        if (!writableOrWarn()) return
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                        InventoryCsvCodec.read(
                            input = input,
                            existing = emptyList(),
                            mode = InventoryImportMode.REPLACE,
                        )
                    } ?: error("无法读取豆仓 CSV")
                }
            }.getOrElse { error ->
                showMessage(error.message ?: "豆仓导入失败")
                return@launch
            }
            if (!result.applied) {
                val issue = result.issues.first()
                showMessage("第 ${issue.line} 行：${issue.message}")
                return@launch
            }
            val unknown = result.inventory.firstOrNull { entry ->
                val installed = paletteCatalog.get(entry.paletteId)
                installed == null || installed.colors.none { color -> color.code == entry.colorCode }
            }
            if (unknown != null) {
                showMessage("CSV 含本机色卡中不存在的颜色：${unknown.paletteId} / ${unknown.colorCode}")
                return@launch
            }
            try {
                withPersistenceTransaction {
                    val latest = _uiState.value
                    val inventory = if (replace) {
                        result.inventory
                    } else {
                        LinkedHashMap<String, InventoryEntry>().apply {
                            latest.inventory.forEach { put(it.key, it) }
                            result.inventory.forEach { put(it.key, it) }
                        }.values.toList()
                    }.sortedWith(compareBy(InventoryEntry::paletteId, InventoryEntry::colorCode))
                    val stagedState = latest.copy(inventory = inventory)
                    _uiState.value = stagedState
                    try {
                        persistStateNowLocked(stagedState)
                    } catch (error: Throwable) {
                        invalidatePendingSaves()
                        _uiState.value = _uiState.value.copy(inventory = latest.inventory)
                        throw error
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showMessage(error.message ?: "豆仓导入保存失败，已回滚")
                return@launch
            }
            showMessage("已导入并保存 ${result.importedCount} 条豆仓记录")
        }
    }

    fun receiveProjectPurchases(projectId: String) {
        if (!writableOrWarn()) return
        val target = project(projectId) ?: return
        val plan = MaterialPlanner.plan(target, palette(target.paletteId), _uiState.value.inventory)
        val purchases = plan.filter { it.bagsToBuy > 0 }
        if (purchases.isEmpty()) return showMessage("当前项目无需补货")
        val mutable = _uiState.value.inventory.associateBy(InventoryEntry::key).toMutableMap()
        purchases.forEach { item ->
            val key = "${target.paletteId}::${item.color.code}"
            val current = mutable[key] ?: InventoryEntry(target.paletteId, item.color.code, 0, item.bagSize)
            val received = item.bagsToBuy.toLong() * item.bagSize
            mutable[key] = current.copy(onHand = (current.onHand + received).coerceAtMost(999_999L).toInt())
        }
        _uiState.value = _uiState.value.copy(
            inventory = mutable.values.sortedWith(compareBy(InventoryEntry::paletteId, InventoryEntry::colorCode)),
        )
        scheduleSave(immediate = true)
        showMessage("已按采购单入库 ${purchases.sumOf { it.bagsToBuy }} 袋")
    }

    fun showMessage(message: String) {
        _uiState.value = _uiState.value.copy(message = message)
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    private suspend fun addAndPersistAndOpen(
        project: BeadProject,
        showMaterialSummary: Boolean = false,
    ) = withPersistenceTransaction {
        val previousState = _uiState.value
        val stagedState = stateWithAddedProject(previousState, project)
        _uiState.value = stagedState
        try {
            persistStateNowLocked(stagedState)
        } catch (error: Throwable) {
            invalidatePendingSaves()
            rollbackAddedProject(project.id, previousState)
            throw error
        }
        _uiState.value = _uiState.value.copy(
            openProjectRequestId = project.id,
            materialSummaryRequestProjectId = project.id.takeIf { showMaterialSummary },
        )
    }

    private fun stateWithAddedProject(
        state: DouGridUiState,
        project: BeadProject,
    ): DouGridUiState = state.copy(
            projects = listOf(project) + state.projects,
            activeCraftProjectId = project.id,
            selectedEditorColor = firstUsedColor(project) ?: 0,
            editorRevision = state.editorRevision + 1,
            editorStatsRevision = state.editorStatsRevision + 1,
        )

    private fun rollbackAddedProject(projectId: String, previousState: DouGridUiState) {
        val current = _uiState.value
        _uiState.value = current.copy(
            projects = current.projects.filterNot { it.id == projectId },
            activeCraftProjectId = current.activeCraftProjectId.takeUnless { it == projectId }
                ?: previousState.activeCraftProjectId,
            openProjectRequestId = previousState.openProjectRequestId,
            materialSummaryRequestProjectId = previousState.materialSummaryRequestProjectId,
            selectedEditorColor = previousState.selectedEditorColor,
            editorRevision = current.editorRevision + 1,
            editorStatsRevision = current.editorStatsRevision + 1,
        )
    }

    private fun touchProject(projectId: String) {
        updateProject(projectId, saveDelay = 280) {
            it.copy(
                modifiedAt = now(),
                status = if (it.status == ProjectStatus.COMPLETED) ProjectStatus.READY else it.status,
            )
        }
    }

    private fun updateProject(
        projectId: String,
        saveDelay: Long = 450,
        transform: (BeadProject) -> BeadProject,
    ) {
        val state = _uiState.value
        var found = false
        val projects = state.projects.map { project ->
            if (project.id == projectId) {
                found = true
                transform(project)
            } else project
        }
        if (!found) return
        _uiState.value = state.copy(
            projects = projects,
            editorRevision = state.editorRevision + 1,
            editorStatsRevision = state.editorStatsRevision + 1,
        )
        scheduleSave(delayMillis = saveDelay)
    }

    private fun updateProjectStateOnly(
        projectId: String,
        saveDelay: Long,
        transform: (BeadProject) -> BeadProject,
    ) {
        val state = _uiState.value
        var found = false
        val projects = state.projects.map { project ->
            if (project.id == projectId) {
                found = true
                transform(project)
            } else {
                project
            }
        }
        if (!found) return
        _uiState.value = state.copy(projects = projects)
        scheduleSave(delayMillis = saveDelay)
    }

    private fun history(projectId: String): EditorHistory = histories.getOrPut(projectId) { EditorHistory() }

    private fun writableOrWarn(): Boolean {
        if (persistenceTransactionActive) {
            _uiState.value = _uiState.value.copy(message = "正在保存本地数据，请稍后再修改")
            return false
        }
        if (!persistenceReadOnly) return true
        _uiState.value = _uiState.value.copy(
            message = "本地数据处于只读恢复模式，当前修改不会保存",
        )
        return false
    }

    private fun invalidatePendingSaves() {
        saveEpoch++
        saveJob?.cancel()
        saveJob = null
    }

    private fun scheduleSave(delayMillis: Long = 500, immediate: Boolean = false) {
        if (persistenceReadOnly || persistenceTransactionActive) return
        saveJob?.cancel()
        val epoch = ++saveEpoch
        saveJob = viewModelScope.launch {
            if (!immediate) delay(delayMillis)
            runCatching {
                saveMutex.withLock {
                    if (epoch != saveEpoch) return@withLock
                    val snapshot = persistedSnapshot(_uiState.value)
                    withContext(NonCancellable) { repository.save(snapshot) }
                }
            }.onFailure { error ->
                if (error !is CancellationException && epoch == saveEpoch) {
                    _uiState.value = _uiState.value.copy(
                        message = error.message ?: "自动保存失败",
                        persistenceWarning = "本地保存失败，当前修改可能尚未写入磁盘。",
                    )
                }
            }.onSuccess {
                if (epoch == saveEpoch) {
                    _uiState.value = _uiState.value.copy(
                        persistenceWarning = persistentPersistenceWarning,
                    )
                }
            }
        }
    }

    // Keep staged state, reference files, and the on-disk snapshot committed as one unit.
    private suspend fun <T> withPersistenceTransaction(block: suspend () -> T): T =
        withContext(NonCancellable) {
            persistenceTransactionMutex.withLock {
                persistenceTransactionActive = true
                try {
                    block()
                } finally {
                    persistenceTransactionActive = false
                }
            }
        }

    private suspend fun persistStateNowLocked(state: DouGridUiState) {
        saveJob?.cancel()
        saveJob = null
        saveEpoch++
        val snapshot = persistedSnapshot(state)
        try {
            saveMutex.withLock {
                repository.save(snapshot)
            }
            _uiState.value = _uiState.value.copy(
                persistenceWarning = persistentPersistenceWarning,
            )
        } catch (error: Throwable) {
            if (error !is CancellationException) {
                _uiState.value = _uiState.value.copy(
                    message = error.message ?: "本地保存失败",
                    persistenceWarning = "本地保存失败，当前修改可能尚未写入磁盘。",
                )
            }
            throw error
        }
    }

    private fun persistedSnapshot(state: DouGridUiState) = PersistedAppState(
        projects = state.projects.map { it.copy(grid = it.grid.deepCopy()) },
        inventory = state.inventory.toList(),
        settings = state.settings,
        deletedProjects = state.deletedProjects.map { it.copy(grid = it.grid.deepCopy()) },
    )

    private fun availableInventoryIndices(palette: BeadPalette): IntArray? {
        val codes = _uiState.value.inventory
            .asSequence()
            .filter { it.paletteId == palette.id && it.onHand > 0 }
            .map { it.colorCode }
            .toSet()
        return palette.colors.indices.filter { palette.colors[it].code in codes }.toIntArray().takeIf { it.isNotEmpty() }
    }

    private fun inventoryCapacities(palette: BeadPalette): IntArray {
        val stockByCode = _uiState.value.inventory
            .asSequence()
            .filter { it.paletteId == palette.id }
            .associate { it.colorCode to it.onHand.coerceAtLeast(0) }
        return IntArray(palette.colors.size) { index -> stockByCode[palette.colors[index].code] ?: 0 }
    }

    private fun firstUsedColor(project: BeadProject?): Int? =
        project?.grid?.cells?.firstOrNull { it != EMPTY_CELL }

    private fun remapArchivedGrid(
        source: PatternGrid,
        archivedPalette: BeadPalette,
        installedPalette: BeadPalette,
    ): PaletteRemapResult {
        val exactPaletteMatch = archivedPalette.id == installedPalette.id &&
            archivedPalette.colors.size == installedPalette.colors.size &&
            archivedPalette.colors.indices.all { index ->
                val archived = archivedPalette.colors[index]
                val installed = installedPalette.colors[index]
                archived.code == installed.code && archived.argb == installed.argb
            }
        val installedByCode = installedPalette.colors.indices.associateBy { index ->
            installedPalette.colors[index].code.uppercase(Locale.ROOT)
        }
        val mapping = IntArray(archivedPalette.colors.size) { sourceIndex ->
            val sourceColor = archivedPalette.colors[sourceIndex]
            installedByCode[sourceColor.code.uppercase(Locale.ROOT)]
                ?: ColorMath.nearestColor(sourceColor.opaqueArgb, installedPalette.colors)
        }
        val remapped = source.deepCopy()
        remapped.cells.indices.forEach { index ->
            val colorIndex = remapped.cells[index]
            if (colorIndex != EMPTY_CELL) remapped.cells[index] = mapping[colorIndex]
        }
        return PaletteRemapResult(remapped, exactPaletteMatch)
    }

    private fun normalizeMetadata(value: String, maxLength: Int): String =
        value.filterNot(Char::isISOControl).trim().take(maxLength)

    private fun referencePngForArchive(file: File): ByteArray {
        require(file.isFile) { "项目参考图已丢失，请重新导入原图后再备份" }
        require(file.length() in 1..64L * 1024 * 1024) { "参考图文件过大，无法安全打包" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "旧版参考图无法解码" }
        var sampleSize = 1
        while (
            ((bounds.outWidth + sampleSize - 1L) / sampleSize) *
            ((bounds.outHeight + sampleSize - 1L) / sampleSize) > 4_000_000L
        ) {
            sampleSize *= 2
        }
        val bitmap = try {
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sampleSize },
            ) ?: error("旧版参考图无法解码")
        } catch (error: OutOfMemoryError) {
            throw IllegalArgumentException("旧版参考图过大，无法安全解码", error)
        }
        return try {
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "参考图无法转换为 PNG" }
                output.toByteArray().also { png ->
                    require(png.size <= DouGridArchiveCodec.MAX_REFERENCE_BYTES) {
                        "参考图转为 PNG 后仍然过大"
                    }
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun now(): Long = System.currentTimeMillis()
}
