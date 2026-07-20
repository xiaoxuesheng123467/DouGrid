package com.qiao.dougrid.export

import com.qiao.dougrid.core.BeadPalette
import com.qiao.dougrid.core.ColorMath
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

data class MaterialSubstitution(
    val sourceColorIndex: Int,
    val source: PaletteColor,
    val replacementColorIndex: Int,
    val replacement: PaletteColor,
    val beadCount: Int,
    val replacementSurplus: Int,
    val perceptualDistance: Double,
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

    /**
     * Suggests whole-color replacements that the current inventory can fully cover. Keeping the
     * replacement atomic avoids producing an unpredictable mix of two colors in one visual area.
     */
    fun substitutionSuggestions(
        project: BeadProject,
        palette: BeadPalette,
        inventory: List<InventoryEntry>,
        maxSuggestionsPerColor: Int = 3,
    ): Map<Int, List<MaterialSubstitution>> {
        require(maxSuggestionsPerColor > 0)
        val plan = plan(project, palette, inventory)
        val byIndex = plan.associateBy(PlannedMaterial::colorIndex)
        val stockByCode = inventory
            .asSequence()
            .filter { it.paletteId == project.paletteId }
            .associateBy(InventoryEntry::colorCode)
        val labs = Array(palette.colors.size) { ColorMath.toOklab(palette.colors[it].opaqueArgb) }

        return plan
            .asSequence()
            .filter { it.shortage > 0 && it.needed > 0 }
            .mapNotNull { source ->
                val candidates = palette.colors.indices
                    .asSequence()
                    .filter { it != source.colorIndex }
                    .mapNotNull { replacementIndex ->
                        val replacement = palette.colors[replacementIndex]
                        val alreadyNeeded = byIndex[replacementIndex]?.needed ?: 0
                        val onHand = stockByCode[replacement.code]?.onHand ?: 0
                        val surplus = (onHand - alreadyNeeded).coerceAtLeast(0)
                        if (surplus < source.needed) return@mapNotNull null
                        MaterialSubstitution(
                            sourceColorIndex = source.colorIndex,
                            source = source.color,
                            replacementColorIndex = replacementIndex,
                            replacement = replacement,
                            beadCount = source.needed,
                            replacementSurplus = surplus,
                            perceptualDistance = kotlin.math.sqrt(
                                labs[source.colorIndex].distanceSquared(labs[replacementIndex]),
                            ),
                        )
                    }
                    .sortedWith(
                        compareBy<MaterialSubstitution>(MaterialSubstitution::perceptualDistance)
                            .thenBy { it.replacement.code },
                    )
                    .take(maxSuggestionsPerColor)
                    .toList()
                candidates.takeIf(List<MaterialSubstitution>::isNotEmpty)
                    ?.let { source.colorIndex to it }
            }
            .toMap()
    }

    private fun singleLine(value: String): String = value.replace(Regex("[\\r\\n\\t]+"), " ").trim()
}
