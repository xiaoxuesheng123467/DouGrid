package com.qiao.dougrid.export

import com.qiao.dougrid.core.BeadPalette
import com.qiao.dougrid.core.PaletteColor
import com.qiao.dougrid.core.PatternGrid
import com.qiao.dougrid.data.BeadProject
import com.qiao.dougrid.data.InventoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MaterialPlannerTest {
    private val palette = BeadPalette(
        id = "test-palette",
        title = "测试色卡",
        version = "1",
        source = "test",
        colors = listOf(
            PaletteColor("A01", 0x00FF0000, name = "红"),
            PaletteColor("B02", 0x0000FF00, name = "绿"),
        ),
    )

    @Test
    fun planUsesMatchingInventoryAndCalculatesPurchaseBags() {
        val project = BeadProject(
            title = "测试图纸",
            paletteId = palette.id,
            grid = PatternGrid(5, 1, intArrayOf(0, 0, 0, 1, 1)),
        )
        val inventory = listOf(
            InventoryEntry(palette.id, "A01", onHand = 2, bagSize = 10),
            InventoryEntry(palette.id, "B02", onHand = 20, bagSize = 10),
            InventoryEntry("another-palette", "A01", onHand = 999, bagSize = 10),
        )

        val plan = MaterialPlanner.plan(project, palette, inventory)

        assertEquals(2, plan.size)
        assertEquals(1, plan[0].shortage)
        assertEquals(1, plan[0].bagsToBuy)
        assertEquals(0, plan[1].shortage)
        assertEquals(0, plan[1].bagsToBuy)
    }

    @Test
    fun procurementListIncludesOnlyActualShortages() {
        val project = BeadProject(
            title = "测试图纸",
            paletteId = palette.id,
            grid = PatternGrid(3, 1, intArrayOf(0, 0, 1)),
        )
        val text = MaterialPlanner.procurementListText(
            project,
            palette,
            listOf(InventoryEntry(palette.id, "B02", onHand = 1)),
        )

        assertTrue(text.contains("A01  缺 2 颗"))
        assertTrue(text.contains("缺少：2 颗，1 个型号"))
        assertTrue(!text.contains("B02  缺"))
    }

    @Test
    fun substitutionRequiresEnoughSurplusForTheWholeSourceColor() {
        val project = BeadProject(
            title = "替色测试",
            paletteId = palette.id,
            grid = PatternGrid(4, 1, intArrayOf(0, 0, 0, 1)),
        )

        val enough = MaterialPlanner.substitutionSuggestions(
            project,
            palette,
            listOf(InventoryEntry(palette.id, "B02", onHand = 4)),
        )
        assertEquals(1, enough.getValue(0).single().replacementColorIndex)
        assertEquals(3, enough.getValue(0).single().beadCount)

        val insufficient = MaterialPlanner.substitutionSuggestions(
            project,
            palette,
            listOf(InventoryEntry(palette.id, "B02", onHand = 3)),
        )
        assertTrue(insufficient[0].isNullOrEmpty())
    }
}
