package com.qiao.dougrid

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qiao.dougrid.core.BeadPalette
import com.qiao.dougrid.core.CellDelta
import com.qiao.dougrid.core.ConversionMode
import com.qiao.dougrid.core.EMPTY_CELL
import com.qiao.dougrid.core.EditorHistory
import com.qiao.dougrid.core.PatternGrid
import com.qiao.dougrid.data.AppSettings
import com.qiao.dougrid.data.BeadProject
import com.qiao.dougrid.data.BeadTemplate
import com.qiao.dougrid.data.EditorTool
import com.qiao.dougrid.data.InventoryEntry
import com.qiao.dougrid.data.MainDestination
import com.qiao.dougrid.data.PaletteCatalog
import com.qiao.dougrid.data.PersistedAppState
import com.qiao.dougrid.data.ProjectRepository
import com.qiao.dougrid.data.ProjectStatus
import com.qiao.dougrid.data.TemplateCatalog
import com.qiao.dougrid.image.BitmapPatternConverter
import com.qiao.dougrid.image.ImageImportOptions
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val openProjectRequestId: String? = null,
    val materialSummaryRequestProjectId: String? = null,
    val mainDestination: MainDestination = MainDestination.LIBRARY,
    val showTutorial: Boolean = false,
    val message: String? = null,
)

class DouGridViewModel(application: Application) : AndroidViewModel(application) {
    private data class ActiveStroke(
        val projectId: String,
        val replacement: Int,
        val before: LinkedHashMap<Int, Int> = linkedMapOf(),
    )

    val paletteCatalog = PaletteCatalog(application)
    private val repository = ProjectRepository(application)
    private val histories = mutableMapOf<String, EditorHistory>()
    private var saveJob: Job? = null
    private var imageImportJob: Job? = null
    private var imageImportGeneration: Long = 0
    private var activeStroke: ActiveStroke? = null

    private val _uiState = MutableStateFlow(DouGridUiState())
    val uiState: StateFlow<DouGridUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val loaded = repository.load()
            val defaultPalette = paletteCatalog.get(loaded.settings.defaultPaletteId) ?: paletteCatalog.default()
            val templates = TemplateCatalog.builtIns(defaultPalette)
            val projects = loaded.projects.ifEmpty { listOf(TemplateCatalog.instantiate(templates.first())) }
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

    fun project(id: String?): BeadProject? = _uiState.value.projects.firstOrNull { it.id == id }

    fun setEditorTool(tool: EditorTool) {
        _uiState.value = _uiState.value.copy(editorTool = tool)
    }

    fun selectEditorColor(index: Int) {
        _uiState.value = _uiState.value.copy(selectedEditorColor = index, editorTool = EditorTool.PENCIL)
    }

    fun requestOpenProject(id: String) {
        val project = project(id) ?: return
        _uiState.value = _uiState.value.copy(
            openProjectRequestId = id,
            selectedEditorColor = firstUsedColor(project) ?: 0,
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
    ) {
        val targetPalette = palette(paletteId)
        val newProject = BeadProject(
            title = title.trim().ifBlank { "未命名作品" },
            paletteId = targetPalette.id,
            grid = PatternGrid(width.coerceIn(8, 256), height.coerceIn(8, 256)),
            sourceMode = ConversionMode.SPRITE,
        )
        addAndOpen(newProject)
    }

    fun createFromTemplate(templateId: String) {
        val template = _uiState.value.templates.firstOrNull { it.id == templateId } ?: return
        addAndOpen(TemplateCatalog.instantiate(template))
    }

    fun importImage(
        uri: Uri,
        title: String,
        paletteId: String,
        options: ImageImportOptions,
    ) {
        if (_uiState.value.isProcessingImage) return
        val generation = ++imageImportGeneration
        _uiState.value = _uiState.value.copy(isProcessingImage = true, message = null)
        imageImportJob = viewModelScope.launch {
            try {
                val targetPalette = palette(paletteId)
                val allowed = if (options.useInventoryOnly) availableInventoryIndices(targetPalette) else null
                if (options.useInventoryOnly && (allowed == null || allowed.size < 2)) {
                    error("豆仓里至少要有两种当前色卡的颜色")
                }
                BitmapPatternConverter.prepareImport(
                    context = getApplication(),
                    uri = uri,
                    palette = targetPalette,
                    options = options,
                    allowedPaletteIndices = allowed,
                ).use { prepared ->
                    currentCoroutineContext().ensureActive()
                    if (generation != imageImportGeneration) return@use
                    val draft = BeadProject(
                        title = title.trim().ifBlank { "图片图纸" },
                        paletteId = targetPalette.id,
                        grid = prepared.grid,
                        sourceMode = options.mode,
                        status = ProjectStatus.READY,
                    )
                    var keepReference = false
                    try {
                        val sourcePath = repository.saveReference(prepared.referenceBitmap, draft.id)
                        currentCoroutineContext().ensureActive()
                        if (generation != imageImportGeneration) return@use
                        addAndOpen(draft.copy(sourcePath = sourcePath), showMaterialSummary = true)
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
        beginEditorStroke(projectId, colorIndex)
        extendEditorStroke(projectId, indices)
        endEditorStroke(projectId)
    }

    fun beginEditorStroke(projectId: String, colorIndex: Int? = null) {
        val project = project(projectId) ?: return
        val replacement = colorIndex ?: when (_uiState.value.editorTool) {
            EditorTool.ERASER -> EMPTY_CELL
            else -> _uiState.value.selectedEditorColor
        }
        activeStroke = ActiveStroke(project.id, replacement)
    }

    fun extendEditorStroke(projectId: String, indices: Collection<Int>) {
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
                grid.cells[index] = session.replacement
                changed = true
            }
        }
        if (changed) {
            val state = _uiState.value
            _uiState.value = state.copy(editorRevision = state.editorRevision + 1)
        }
    }

    fun endEditorStroke(projectId: String) {
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
        )
        history(projectId).record(delta)
        touchProject(projectId)
    }

    fun applyToolAt(projectId: String, index: Int) {
        val project = project(projectId) ?: return
        if (index !in project.grid.cells.indices) return
        when (_uiState.value.editorTool) {
            EditorTool.PENCIL -> applyStroke(projectId, listOf(index))
            EditorTool.ERASER -> applyStroke(projectId, listOf(index), EMPTY_CELL)
            EditorTool.FILL -> {
                val x = index % project.grid.width
                val y = index / project.grid.width
                val delta = project.grid.floodFill(x, y, _uiState.value.selectedEditorColor)
                history(projectId).record(delta)
                if (delta != null) touchProject(projectId)
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
                    history(projectId).record(delta)
                    if (delta != null) touchProject(projectId)
                }
            }
            EditorTool.PAN -> Unit
        }
    }

    fun mirror(projectId: String, horizontal: Boolean) {
        val project = project(projectId) ?: return
        val delta = if (horizontal) project.grid.mirrorHorizontal() else project.grid.mirrorVertical()
        history(projectId).record(delta)
        touchProject(projectId)
    }

    fun undo(projectId: String) {
        val project = project(projectId) ?: return
        if (history(projectId).undo(project.grid) != null) touchProject(projectId)
    }

    fun redo(projectId: String) {
        val project = project(projectId) ?: return
        if (history(projectId).redo(project.grid) != null) touchProject(projectId)
    }

    fun canUndo(projectId: String): Boolean = histories[projectId]?.canUndo == true

    fun canRedo(projectId: String): Boolean = histories[projectId]?.canRedo == true

    fun renameProject(projectId: String, title: String) {
        updateProject(projectId) { it.copy(title = title.trim().ifBlank { it.title }, modifiedAt = now()) }
    }

    fun toggleFavorite(projectId: String) {
        updateProject(projectId) { it.copy(favorite = !it.favorite, modifiedAt = now()) }
    }

    fun duplicateProject(projectId: String) {
        val source = project(projectId) ?: return
        val duplicate = source.copy(
            id = java.util.UUID.randomUUID().toString(),
            title = "${source.title} 副本",
            grid = source.grid.deepCopy().also { it.completed.fill(0) },
            createdAt = now(),
            modifiedAt = now(),
            status = ProjectStatus.DRAFT,
            inventoryDeducted = false,
            sourcePath = null,
        )
        viewModelScope.launch {
            var keepReference = false
            try {
                val copiedReference = source.sourcePath?.let { path ->
                    repository.copyReference(path, duplicate.id)
                }
                currentCoroutineContext().ensureActive()
                addAndOpen(duplicate.copy(sourcePath = copiedReference))
                keepReference = true
            } finally {
                if (!keepReference) {
                    withContext(NonCancellable) { repository.deleteProjectReferences(duplicate.id) }
                }
            }
        }
    }

    fun deleteProject(projectId: String) {
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
        val state = _uiState.value
        val target = state.deletedProjects.firstOrNull { it.id == projectId } ?: return
        _uiState.value = state.copy(
            projects = listOf(target.copy(modifiedAt = now())) + state.projects,
            deletedProjects = state.deletedProjects.filterNot { it.id == projectId },
        )
        scheduleSave()
    }

    fun emptyTrash() {
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

    fun toggleCraftCell(projectId: String, index: Int) {
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
        val project = project(projectId) ?: return
        project.grid.completed.fill(0)
        updateProject(projectId) { it.copy(status = ProjectStatus.READY, modifiedAt = now()) }
    }

    fun completeProject(projectId: String, deductInventory: Boolean) {
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
        val state = _uiState.value
        val key = "$paletteId::$colorCode"
        val mutable = state.inventory.associateBy { it.key }.toMutableMap()
        val current = mutable[key] ?: InventoryEntry(paletteId, colorCode, 0)
        val updated = current.copy(
            onHand = amount.coerceIn(0, 999_999),
            bagSize = bagSize?.coerceAtLeast(1) ?: current.bagSize,
        )
        if (updated.onHand == 0 && bagSize == null) mutable.remove(key) else mutable[key] = updated
        _uiState.value = state.copy(
            inventory = mutable.values.sortedWith(compareBy(InventoryEntry::paletteId, InventoryEntry::colorCode)),
        )
        scheduleSave()
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val updated = transform(_uiState.value.settings)
        val defaultPalette = palette(updated.defaultPaletteId)
        _uiState.value = _uiState.value.copy(
            settings = updated.copy(defaultPaletteId = defaultPalette.id),
            templates = TemplateCatalog.builtIns(defaultPalette),
        )
        scheduleSave()
    }

    fun showMessage(message: String) {
        _uiState.value = _uiState.value.copy(message = message)
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    private fun addAndOpen(project: BeadProject, showMaterialSummary: Boolean = false) {
        val state = _uiState.value
        _uiState.value = state.copy(
            projects = listOf(project) + state.projects,
            activeCraftProjectId = project.id,
            openProjectRequestId = project.id,
            materialSummaryRequestProjectId = project.id.takeIf { showMaterialSummary },
            selectedEditorColor = firstUsedColor(project) ?: 0,
            editorRevision = state.editorRevision + 1,
            editorStatsRevision = state.editorStatsRevision + 1,
        )
        scheduleSave(immediate = true)
    }

    private fun touchProject(projectId: String) {
        updateProject(projectId, saveDelay = 280) { it.copy(modifiedAt = now()) }
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

    private fun history(projectId: String): EditorHistory = histories.getOrPut(projectId) { EditorHistory() }

    private fun scheduleSave(delayMillis: Long = 500, immediate: Boolean = false) {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            if (!immediate) delay(delayMillis)
            val state = _uiState.value
            runCatching {
                repository.save(
                    PersistedAppState(
                        projects = state.projects,
                        inventory = state.inventory,
                        settings = state.settings,
                        deletedProjects = state.deletedProjects,
                    ),
                )
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(message = error.message ?: "自动保存失败")
            }
        }
    }

    private fun availableInventoryIndices(palette: BeadPalette): IntArray? {
        val codes = _uiState.value.inventory
            .asSequence()
            .filter { it.paletteId == palette.id && it.onHand > 0 }
            .map { it.colorCode }
            .toSet()
        return palette.colors.indices.filter { palette.colors[it].code in codes }.toIntArray().takeIf { it.isNotEmpty() }
    }

    private fun firstUsedColor(project: BeadProject?): Int? =
        project?.grid?.cells?.firstOrNull { it != EMPTY_CELL }

    private fun now(): Long = System.currentTimeMillis()
}
