package com.qiao.dougrid.data

import com.qiao.dougrid.core.ConversionMode
import com.qiao.dougrid.core.PatternGrid
import java.util.UUID

enum class ProjectStatus { DRAFT, READY, CRAFTING, COMPLETED, ARCHIVED }

data class BeadProject(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val paletteId: String,
    val grid: PatternGrid,
    val sourceMode: ConversionMode = ConversionMode.SPRITE,
    val createdAt: Long = System.currentTimeMillis(),
    val modifiedAt: Long = System.currentTimeMillis(),
    val status: ProjectStatus = ProjectStatus.DRAFT,
    val favorite: Boolean = false,
    val inventoryDeducted: Boolean = false,
    val sourcePath: String? = null,
    val boardSize: Int = DEFAULT_BOARD_SIZE,
    val craftElapsedSeconds: Long = 0L,
    val lastCraftBoardIndex: Int = 0,
    val tags: List<String> = emptyList(),
    val folder: String? = null,
) {
    val boardColumns: Int get() = (grid.width + boardSize - 1) / boardSize
    val boardRows: Int get() = (grid.height + boardSize - 1) / boardSize
    val boardCount: Int get() = boardColumns * boardRows
    val progress: Float
        get() = if (grid.beadCount() == 0) 0f else grid.completedCount().toFloat() / grid.beadCount()

    fun withGrid(grid: PatternGrid, now: Long = System.currentTimeMillis()): BeadProject =
        copy(grid = grid, modifiedAt = now)

    fun boardProgress(boardIndex: Int): Float {
        if (boardIndex !in 0 until boardCount) return 0f
        val boardColumn = boardIndex % boardColumns
        val boardRow = boardIndex / boardColumns
        val startColumn = boardColumn * boardSize
        val startRow = boardRow * boardSize
        val endColumn = minOf(startColumn + boardSize, grid.width)
        val endRow = minOf(startRow + boardSize, grid.height)
        var total = 0
        var completed = 0
        for (row in startRow until endRow) {
            for (column in startColumn until endColumn) {
                val index = grid.indexOf(column, row)
                if (grid.cells[index] == com.qiao.dougrid.core.EMPTY_CELL) continue
                total++
                if (grid.completed[index].toInt() != 0) completed++
            }
        }
        return if (total == 0) 0f else completed.toFloat() / total
    }

    companion object {
        const val DEFAULT_BOARD_SIZE = 29
        const val MIN_BOARD_SIZE = 8
        const val MAX_BOARD_SIZE = 64

        @Deprecated("Use the per-project boardSize property")
        const val BOARD_SIZE = DEFAULT_BOARD_SIZE
    }
}

data class InventoryEntry(
    val paletteId: String,
    val colorCode: String,
    val onHand: Int,
    val bagSize: Int = 1_000,
) {
    val key: String get() = "$paletteId::$colorCode"
}

enum class AppThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val defaultPaletteId: String = "mard-221",
    val showColorCodes: Boolean = true,
    val highContrastGrid: Boolean = false,
    val keepScreenOnInCraftMode: Boolean = true,
    val confirmInventoryDeduction: Boolean = true,
    val hasSeenTutorial: Boolean = false,
    val defaultBoardSize: Int = BeadProject.DEFAULT_BOARD_SIZE,
    val lowStockThreshold: Int = 300,
)

data class PersistedAppState(
    val projects: List<BeadProject>,
    val inventory: List<InventoryEntry>,
    val settings: AppSettings,
    val deletedProjects: List<BeadProject> = emptyList(),
)

enum class EditorTool { PENCIL, ERASER, FILL, PICKER, REPLACE, SELECT, PAN }

enum class MainDestination { LIBRARY, CRAFT, INVENTORY }
