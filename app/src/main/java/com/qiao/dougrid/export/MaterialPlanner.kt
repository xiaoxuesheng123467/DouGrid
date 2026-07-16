package com.qiao.dougrid.export

import com.qiao.dougrid.core.BeadPalette
import com.qiao.dougrid.core.PaletteColor
import com.qiao.dougrid.data.BeadProject
import com.qiao.dougrid.data.InventoryEntry
import kotlin.math.ceil

data class PlannedMaterial(
    val colorIndex: Int,
    val color: PaletteColor,
    val symbol: String,
    val needed: Int,
    val onHand: Int,
    val shortage: Int,
    val bagSize: Int,
    val bagsToBuy: Int,
)

object MaterialPlanner {
    private const val DEFAULT_BAG_SIZE = 1_000

    fun plan(
        project: BeadProject,
        palette: BeadPalette,
        inventory: List<InventoryEntry>,
    ): List<PlannedMaterial> {
        val stockByCode = inventory
            .asSequence()
            .filter { it.paletteId == project.paletteId }
            .associateBy { it.colorCode }

        return PatternExporter.materials(project, palette).map { material ->
            val stock = stockByCode[material.color.code]
            val onHand = stock?.onHand ?: 0
            val bagSize = stock?.bagSize?.coerceAtLeast(1) ?: DEFAULT_BAG_SIZE
            val shortage = (material.beadCount - onHand).coerceAtLeast(0)
            PlannedMaterial(
                colorIndex = material.colorIndex,
                color = material.color,
                symbol = material.symbol,
                needed = material.beadCount,
                onHand = onHand,
                shortage = shortage,
                bagSize = bagSize,
                bagsToBuy = if (shortage == 0) 0 else ceil(shortage.toDouble() / bagSize).toInt(),
            )
        }
    }

    fun procurementListText(
        project: BeadProject,
        palette: BeadPalette,
        inventory: List<InventoryEntry>,
    ): String {
        val plan = plan(project, palette, inventory)
        val shortages = plan.filter { it.shortage > 0 }
        return buildString {
            appendLine("${singleLine(project.title)} · 拼豆采购单")
            appendLine("色卡：${singleLine(palette.title)}")
            appendLine("尺寸：${project.grid.width} × ${project.grid.height}，共 ${project.grid.beadCount()} 颗")
            appendLine("缺少：${shortages.sumOf { it.shortage }} 颗，${shortages.size} 个型号")
            appendLine()
            if (shortages.isEmpty()) {
                appendLine("豆仓库存已满足当前图纸。")
            } else {
                shortages.forEach { item ->
                    appendLine(
                        "${item.color.code}  缺 ${item.shortage} 颗  需买 ${item.bagsToBuy} 袋" +
                            "（每袋 ${item.bagSize}，图纸需要 ${item.needed}）",
                    )
                }
            }
        }
    }

    private fun singleLine(value: String): String = value.replace(Regex("[\\r\\n\\t]+"), " ").trim()
}
