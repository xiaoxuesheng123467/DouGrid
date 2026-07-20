package com.qiao.dougrid.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.qiao.dougrid.core.BeadPalette
import com.qiao.dougrid.core.ConversionMode
import com.qiao.dougrid.core.EMPTY_CELL
import com.qiao.dougrid.core.PaletteColor
import com.qiao.dougrid.core.PatternGrid
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class DouGridArchiveCodecTest {
    private val palette = BeadPalette(
        id = "test-palette",
        title = "测试色卡",
        version = "2026.07",
        source = "offline-test",
        colors = listOf(
            PaletteColor("A01", 0xFFFF0000.toInt(), group = "暖色", name = "红色"),
            PaletteColor("B02", 0xFF00FF00.toInt(), group = "冷色", name = "绿色"),
        ),
    )

    @Test
    fun archiveRoundTripPreservesProjectPaletteReferenceAndCraftMetadata() {
        val project = BeadProject(
            id = "round-trip-project",
            title = "周末作品",
            paletteId = palette.id,
            grid = PatternGrid(
                3,
                2,
                intArrayOf(0, 1, EMPTY_CELL, 1, 0, 0),
                byteArrayOf(1, 0, 0, 1, 0, 0),
            ),
            sourceMode = ConversionMode.PHOTO,
            createdAt = 100,
            modifiedAt = 200,
            status = ProjectStatus.CRAFTING,
            favorite = true,
            sourcePath = "/private/device/path/reference.png",
            boardSize = 16,
            craftElapsedSeconds = 3_661,
            lastCraftBoardIndex = 0,
            tags = listOf("礼物", "进行中"),
            folder = "夏日计划",
        )
        val reference = onePixelPng()
        val craft = DouGridCraftMetadata.fromProject(
            project,
            highlightedColorCode = "B02",
            hideCompleted = true,
        )
        val output = ByteArrayOutputStream()

        DouGridArchiveCodec.write(output, project, palette, reference, craft, exportedAt = 123_456)
        val imported = DouGridArchiveCodec.read(ByteArrayInputStream(output.toByteArray()))

        assertEquals(DouGridArchiveCodec.ARCHIVE_VERSION, imported.archiveVersion)
        assertEquals(123_456L, imported.exportedAt)
        assertEquals(project.id, imported.project.id)
        assertEquals(project.title, imported.project.title)
        assertEquals(project.sourceMode, imported.project.sourceMode)
        assertEquals(project.status, imported.project.status)
        assertEquals(project.boardSize, imported.project.boardSize)
        assertEquals(project.craftElapsedSeconds, imported.project.craftElapsedSeconds)
        assertEquals(project.lastCraftBoardIndex, imported.project.lastCraftBoardIndex)
        assertEquals(project.tags, imported.project.tags)
        assertEquals(project.folder, imported.project.folder)
        assertNull(imported.project.sourcePath)
        assertArrayEquals(project.grid.cells, imported.project.grid.cells)
        assertArrayEquals(project.grid.completed, imported.project.grid.completed)
        assertEquals(palette, imported.paletteSnapshot)
        assertEquals(craft, imported.craftMetadata)
        assertArrayEquals(reference, imported.referencePng)
    }

    @Test
    fun archiveRejectsUnsafeEntryPaths() {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("../manifest.json"))
            zip.write("{}".toByteArray())
            zip.closeEntry()
        }

        val error = archiveFailure(output.toByteArray())

        assertEquals(DouGridArchiveError.INVALID_FORMAT, error.error)
    }

    @Test
    fun archiveRejectsCorruptCompletionPayload() {
        val project = BeadProject(
            id = "corrupt-completion",
            title = "Corrupt completion",
            paletteId = palette.id,
            grid = PatternGrid(2, 1, intArrayOf(0, 1), byteArrayOf(1, 0)),
        )
        val valid = ByteArrayOutputStream().also { DouGridArchiveCodec.write(it, project, palette) }.toByteArray()
        val corrupt = rewriteEntry(valid, "grid/completed.bin") { byteArrayOf(2, 0) }

        val error = archiveFailure(corrupt)

        assertEquals(DouGridArchiveError.INVALID_PROJECT, error.error)
    }

    @Test
    fun archiveMigratesLegacyCompletionMarksOnEmptyCells() {
        val project = BeadProject(
            id = "legacy-completion",
            title = "Legacy completion",
            paletteId = palette.id,
            grid = PatternGrid(
                3,
                1,
                intArrayOf(0, EMPTY_CELL, 1),
                byteArrayOf(1, 0, 1),
            ),
        )
        val valid = ByteArrayOutputStream().also { DouGridArchiveCodec.write(it, project, palette) }.toByteArray()
        val legacyCompleted = rewriteEntry(valid, "grid/completed.bin") { bytes ->
            bytes.copyOf().also { it[1] = 1 }
        }
        val legacyArchive = rewriteEntry(legacyCompleted, "craft.json") { bytes ->
            JSONObject(String(bytes, StandardCharsets.UTF_8))
                .put("completionMarks", 3)
                .toString()
                .toByteArray(StandardCharsets.UTF_8)
        }

        val imported = DouGridArchiveCodec.read(ByteArrayInputStream(legacyArchive))

        assertArrayEquals(byteArrayOf(1, 0, 1), imported.project.grid.completed)
        assertEquals(2, imported.craftMetadata.completionMarks)
    }

    @Test
    fun exportRejectsInvalidReferenceImage() {
        val project = BeadProject(
            id = "bad-reference",
            title = "Bad reference",
            paletteId = palette.id,
            grid = PatternGrid(1, 1, intArrayOf(0)),
        )

        val error = try {
            DouGridArchiveCodec.write(ByteArrayOutputStream(), project, palette, ByteArray(64))
            throw AssertionError("Expected invalid reference image")
        } catch (error: DouGridArchiveException) {
            error
        }

        assertEquals(DouGridArchiveError.INVALID_REFERENCE_IMAGE, error.error)
    }

    @Test
    fun exportRejectsStructurallyValidButUndecodableReferenceImage() {
        val project = BeadProject(
            id = "bad-reference-data",
            title = "Bad reference data",
            paletteId = palette.id,
            grid = PatternGrid(1, 1, intArrayOf(0)),
        )
        val compressedEmptyImage = ByteArrayOutputStream().also { output ->
            DeflaterOutputStream(output).use { }
        }.toByteArray()

        val error = try {
            DouGridArchiveCodec.write(
                ByteArrayOutputStream(),
                project,
                palette,
                pngWithImageData(compressedEmptyImage),
            )
            throw AssertionError("Expected undecodable reference image")
        } catch (error: DouGridArchiveException) {
            error
        }

        assertEquals(DouGridArchiveError.INVALID_REFERENCE_IMAGE, error.error)
    }

    private fun archiveFailure(bytes: ByteArray): DouGridArchiveException = try {
        DouGridArchiveCodec.read(ByteArrayInputStream(bytes))
        throw AssertionError("Expected archive import to fail")
    } catch (error: DouGridArchiveException) {
        error
    }

    private fun rewriteEntry(
        archive: ByteArray,
        targetName: String,
        transform: (ByteArray) -> ByteArray,
    ): ByteArray {
        val entries = mutableListOf<Pair<String, ByteArray>>()
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val bytes = zip.readBytes()
                entries += entry.name to if (entry.name == targetName) transform(bytes) else bytes
            }
        }
        assertTrue(entries.any { it.first == targetName })
        return ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                entries.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }.toByteArray()
    }

    private fun onePixelPng(): ByteArray {
        val compressed = ByteArrayOutputStream().also { output ->
            DeflaterOutputStream(output).use { it.write(byteArrayOf(0, 0xCC.toByte(), 0x44, 0x88.toByte(), 0xFF.toByte())) }
        }.toByteArray()
        return pngWithImageData(compressed)
    }

    private fun pngWithImageData(compressed: ByteArray): ByteArray {
        return ByteArrayOutputStream().also { output ->
            output.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            output.writeChunk("IHDR", ByteArrayOutputStream().also { header ->
                DataOutputStream(header).use { data ->
                    data.writeInt(1)
                    data.writeInt(1)
                    data.writeByte(8)
                    data.writeByte(6)
                    data.writeByte(0)
                    data.writeByte(0)
                    data.writeByte(0)
                }
            }.toByteArray())
            output.writeChunk("IDAT", compressed)
            output.writeChunk("IEND", byteArrayOf())
        }.toByteArray()
    }

    private fun ByteArrayOutputStream.writeChunk(type: String, data: ByteArray) {
        val typeBytes = type.toByteArray(StandardCharsets.US_ASCII)
        DataOutputStream(this).apply {
            writeInt(data.size)
            write(typeBytes)
            write(data)
            val crc = CRC32().apply {
                update(typeBytes)
                update(data)
            }.value
            writeInt(crc.toInt())
        }
    }
}
