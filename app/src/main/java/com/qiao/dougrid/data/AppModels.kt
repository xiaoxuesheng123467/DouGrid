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
) {
    val boardColumns: Int get() = (grid.width + BOARD_SIZE - 1) / BOARD_SIZE
    val boardRows: Int get() = (grid.height + BOARD_SIZE - 1) / BOARD_SIZE
    val boardCount: Int get() = boardColumns * boardRows
    val progress: Float
        get() = if (grid.beadCount() == 0) 0f else grid.completedCount().toFloat() / grid.beadCount()

    fun withGrid(grid: PatternGrid, now: Long = System.currentTimeMillis()): BeadProject =
        copy(grid = grid, modifiedAt = now)

    companion object {
        const val BOARD_SIZE = 29
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
)

data class PersistedAppState(
    val projects: List<BeadProject>,
    val inventory: List<InventoryEntry>,
    val settings: AppSettings,
    val deletedProjects: List<BeadProject> = emptyList(),
)

enum class EditorTool { PENCIL, ERASER, FILL, PICKER, REPLACE, PAN }

enum class MainDestination { LIBRARY, CRAFT, INVENTORY }
