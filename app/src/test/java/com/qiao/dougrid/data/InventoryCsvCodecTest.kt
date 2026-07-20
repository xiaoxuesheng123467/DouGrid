package com.qiao.dougrid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InventoryCsvCodecTest {
    @Test
    fun encodeQuotesEveryFieldAndRoundTripsEscapedCharacters() {
        val source = listOf(
            InventoryEntry("mard-221", "A,\"01", onHand = 125, bagSize = 1_000),
            InventoryEntry("coco-291", "B02", onHand = 0, bagSize = 500),
        )

        val csv = InventoryCsvCodec.encode(source)
        val result = InventoryCsvCodec.decode(csv, mode = InventoryImportMode.REPLACE)

        assertTrue(csv.startsWith("\"dougrid_inventory_version\",\"palette_id\""))
        assertTrue(csv.contains("\"A,\"\"01\""))
        assertTrue(result.applied)
        assertEquals(source.sortedBy { it.paletteId }, result.inventory)
    }

    @Test
    fun mergeUpdatesMatchingKeysAndPreservesOtherInventory() {
        val existing = listOf(
            InventoryEntry("mard-221", "A01", 10, 1_000),
            InventoryEntry("mard-221", "B02", 20, 500),
        )
        val csv = InventoryCsvCodec.encode(
            listOf(
                InventoryEntry("mard-221", "A01", 99, 200),
                InventoryEntry("coco-291", "C03", 30, 300),
            ),
        )

        val result = InventoryCsvCodec.decode(csv, existing, InventoryImportMode.MERGE)

        assertTrue(result.applied)
        assertEquals(2, result.importedCount)
        assertEquals(3, result.inventory.size)
        assertEquals(99, result.inventory.first { it.key == "mard-221::A01" }.onHand)
        assertEquals(20, result.inventory.first { it.key == "mard-221::B02" }.onHand)
    }

    @Test
    fun replaceDropsEntriesNotPresentInCsv() {
        val existing = listOf(InventoryEntry("mard-221", "A01", 10))
        val replacement = listOf(InventoryEntry("coco-291", "C03", 30, 300))

        val result = InventoryCsvCodec.decode(
            InventoryCsvCodec.encode(replacement),
            existing,
            InventoryImportMode.REPLACE,
        )

        assertTrue(result.applied)
        assertEquals(replacement, result.inventory)
    }

    @Test
    fun invalidRowLeavesExistingInventoryUntouched() {
        val existing = listOf(InventoryEntry("mard-221", "A01", 10))
        val csv = InventoryCsvCodec.encode(listOf(InventoryEntry("mard-221", "B02", 20)))
            .replace("\"20\",\"1000\"", "\"-1\",\"1000\"")

        val result = InventoryCsvCodec.decode(csv, existing, InventoryImportMode.MERGE)

        assertFalse(result.applied)
        assertEquals(existing, result.inventory)
        assertEquals(0, result.importedCount)
        assertEquals(InventoryCsvIssueCode.INVALID_ON_HAND, result.issues.single().code)
    }

    @Test
    fun duplicateAndMalformedRowsAreRejected() {
        val header = InventoryCsvCodec.encode(emptyList())
        val row = "\"1\",\"mard-221\",\"A01\",\"10\",\"1000\"\r\n"

        val duplicate = InventoryCsvCodec.decode(header + row + row)
        val malformed = InventoryCsvCodec.decode(header + "\"1\",\"unterminated")

        assertFalse(duplicate.applied)
        assertEquals(InventoryCsvIssueCode.DUPLICATE_ENTRY, duplicate.issues.single().code)
        assertFalse(malformed.applied)
        assertEquals(InventoryCsvIssueCode.MALFORMED_CSV, malformed.issues.single().code)
    }

    @Test
    fun spreadsheetFormulaColorCodesAreRejected() {
        val header = InventoryCsvCodec.encode(emptyList())
        val csv = header + "\"1\",\"mard-221\",\"=1+1\",\"10\",\"1000\"\r\n"

        val result = InventoryCsvCodec.decode(csv)

        assertFalse(result.applied)
        assertEquals(InventoryCsvIssueCode.INVALID_COLOR_CODE, result.issues.single().code)
    }
}
