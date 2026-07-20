package com.qiao.dougrid.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.qiao.dougrid.core.BeadPalette
import com.qiao.dougrid.core.ConversionMode
import com.qiao.dougrid.core.EMPTY_CELL
import com.qiao.dougrid.core.PaletteColor
import com.qiao.dougrid.core.PatternGrid
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

enum class DouGridArchiveError {
    INVALID_FORMAT,
    UNSUPPORTED_VERSION,
    SIZE_LIMIT_EXCEEDED,
    INVALID_PROJECT,
    INVALID_PALETTE,
    INVALID_CRAFT_METADATA,
    INVALID_REFERENCE_IMAGE,
    IO_ERROR,
}

class DouGridArchiveException(
    val error: DouGridArchiveError,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

data class DouGridCraftMetadata(
    val boardSize: Int,
    val elapsedSeconds: Long,
    val lastBoardIndex: Int,
    val completionMarks: Int,
    val highlightedColorCode: String? = null,
    val hideCompleted: Boolean = false,
) {
    companion object {
        fun fromProject(
            project: BeadProject,
            highlightedColorCode: String? = null,
            hideCompleted: Boolean = false,
        ) = DouGridCraftMetadata(
            boardSize = project.boardSize,
            elapsedSeconds = project.craftElapsedSeconds,
            lastBoardIndex = project.lastCraftBoardIndex,
            completionMarks = project.grid.completedCount(),
            highlightedColorCode = highlightedColorCode,
            hideCompleted = hideCompleted,
        )
    }
}

data class DouGridProjectArchive(
    val project: BeadProject,
    val paletteSnapshot: BeadPalette,
    val craftMetadata: DouGridCraftMetadata,
    val referencePng: ByteArray?,
    val archiveVersion: Int,
    val exportedAt: Long,
)

/**
 * Versioned, offline-only project interchange. The caller owns the supplied
 * streams. Imported projects never retain filesystem paths from the source
 * device; persist [DouGridProjectArchive.referencePng] locally before assigning
 * a new `sourcePath`.
 */
object DouGridArchiveCodec {
    const val FILE_EXTENSION = "dougrid"
    const val MIME_TYPE = "application/vnd.dougrid.project+zip"
    const val ARCHIVE_VERSION = 1
    const val MAX_COMPRESSED_BYTES = 24 * 1024 * 1024
    const val MAX_REFERENCE_BYTES = 16 * 1024 * 1024

    private const val ARCHIVE_SCHEMA = "com.qiao.dougrid.project-archive"
    private const val PROJECT_SCHEMA = "com.qiao.dougrid.project"
    private const val PALETTE_SCHEMA = "com.qiao.dougrid.palette-snapshot"
    private const val CRAFT_SCHEMA = "com.qiao.dougrid.craft-metadata"
    private const val PAYLOAD_VERSION = 1
    private const val MAX_TOTAL_UNCOMPRESSED_BYTES = 20 * 1024 * 1024
    private const val MAX_PALETTE_COLORS = 2_048
    private const val MAX_ELAPSED_SECONDS = 100L * 366 * 24 * 60 * 60
    private const val MAX_PNG_DIMENSION = 8_192
    private const val MAX_PNG_PIXELS = 40_000_000L
    private const val MAX_REFERENCE_DECODE_PIXELS = 4_000_000L

    private const val MANIFEST_ENTRY = "manifest.json"
    private const val PROJECT_ENTRY = "project.json"
    private const val PALETTE_ENTRY = "palette.json"
    private const val CRAFT_ENTRY = "craft.json"
    private const val CELLS_ENTRY = "grid/cells.i32le"
    private const val COMPLETED_ENTRY = "grid/completed.bin"
    private const val REFERENCE_ENTRY = "reference.png"

    private val requiredEntries = linkedSetOf(
        MANIFEST_ENTRY,
        PROJECT_ENTRY,
        PALETTE_ENTRY,
        CRAFT_ENTRY,
        CELLS_ENTRY,
        COMPLETED_ENTRY,
    )
    private val allowedEntries = requiredEntries + REFERENCE_ENTRY
    private val entryLimits = mapOf(
        MANIFEST_ENTRY to 64 * 1024,
        PROJECT_ENTRY to 256 * 1024,
        PALETTE_ENTRY to 2 * 1024 * 1024,
        CRAFT_ENTRY to 64 * 1024,
        CELLS_ENTRY to 512 * 512 * Int.SIZE_BYTES,
        COMPLETED_ENTRY to 512 * 512,
        REFERENCE_ENTRY to MAX_REFERENCE_BYTES,
    )

    /** Writes a complete archive without closing [output]. */
    fun write(
        output: OutputStream,
        project: BeadProject,
        paletteSnapshot: BeadPalette,
        referencePng: ByteArray? = null,
        craftMetadata: DouGridCraftMetadata = DouGridCraftMetadata.fromProject(project),
        exportedAt: Long = System.currentTimeMillis(),
    ) {
        val snapshot = project.copy(grid = project.grid.deepCopy())
        validateProject(snapshot, paletteSnapshot)
        validatePalette(paletteSnapshot)
        validateCraftMetadata(snapshot, paletteSnapshot, craftMetadata)
        val normalizedReference = referencePng?.let(::validateAndNormalizeReferencePng)
        if (exportedAt < 0) invalid(DouGridArchiveError.INVALID_FORMAT, "Export timestamp is invalid")

        val entries = requiredEntries + if (normalizedReference == null) emptySet() else setOf(REFERENCE_ENTRY)
        val manifest = JSONObject().apply {
            put("schema", ARCHIVE_SCHEMA)
            put("archiveVersion", ARCHIVE_VERSION)
            put("exportedAt", exportedAt)
            put("entries", JSONArray().apply { entries.forEach(::put) })
        }
        val cells = ByteBuffer.allocate(snapshot.grid.size * Int.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply { snapshot.grid.cells.forEach(::putInt) }
            .array()

        try {
            ZipOutputStream(NonClosingOutputStream(output), StandardCharsets.UTF_8).use { zip ->
                zip.setLevel(Deflater.BEST_SPEED)
                zip.writeEntry(MANIFEST_ENTRY, manifest.toString().toByteArray(StandardCharsets.UTF_8))
                zip.writeEntry(PROJECT_ENTRY, encodeProject(snapshot).toString().toByteArray(StandardCharsets.UTF_8))
                zip.writeEntry(PALETTE_ENTRY, encodePalette(paletteSnapshot).toString().toByteArray(StandardCharsets.UTF_8))
                zip.writeEntry(CRAFT_ENTRY, encodeCraft(craftMetadata).toString().toByteArray(StandardCharsets.UTF_8))
                zip.writeEntry(CELLS_ENTRY, cells)
                zip.writeEntry(COMPLETED_ENTRY, snapshot.grid.completed)
                if (normalizedReference != null) zip.writeEntry(REFERENCE_ENTRY, normalizedReference)
            }
        } catch (error: DouGridArchiveException) {
            throw error
        } catch (error: IOException) {
            throw DouGridArchiveException(DouGridArchiveError.IO_ERROR, "Unable to write project archive", error)
        }
    }

    /** Reads and validates a complete archive without closing [input]. */
    fun read(input: InputStream): DouGridProjectArchive = try {
        readArchive(input)
    } catch (error: DouGridArchiveException) {
        throw error
    } catch (error: ZipException) {
        throw DouGridArchiveException(DouGridArchiveError.INVALID_FORMAT, "Archive ZIP data is invalid", error)
    } catch (error: EOFException) {
        throw DouGridArchiveException(DouGridArchiveError.INVALID_FORMAT, "Archive is truncated", error)
    } catch (error: JSONException) {
        throw DouGridArchiveException(DouGridArchiveError.INVALID_FORMAT, "Archive JSON data is invalid", error)
    } catch (error: IllegalArgumentException) {
        throw DouGridArchiveException(DouGridArchiveError.INVALID_FORMAT, "Archive data is invalid", error)
    } catch (error: IOException) {
        throw DouGridArchiveException(DouGridArchiveError.IO_ERROR, "Unable to read project archive", error)
    }

    private fun readArchive(input: InputStream): DouGridProjectArchive {
        val compressedInput = CountingLimitInputStream(input, MAX_COMPRESSED_BYTES.toLong())
        val contents = linkedMapOf<String, ByteArray>()
        var totalUncompressed = 0L

        ZipInputStream(compressedInput, StandardCharsets.UTF_8).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                validateEntryPath(name)
                if (entry.isDirectory || name !in allowedEntries) {
                    invalid(DouGridArchiveError.INVALID_FORMAT, "Unexpected archive entry '$name'")
                }
                if (contents.containsKey(name)) {
                    invalid(DouGridArchiveError.INVALID_FORMAT, "Duplicate archive entry '$name'")
                }
                if (entry.method != ZipEntry.DEFLATED && entry.method != ZipEntry.STORED) {
                    invalid(DouGridArchiveError.INVALID_FORMAT, "Unsupported ZIP method for '$name'")
                }
                val limit = entryLimits.getValue(name)
                if (entry.size > limit) {
                    invalid(DouGridArchiveError.SIZE_LIMIT_EXCEEDED, "Archive entry '$name' is too large")
                }
                val payload = zip.readCurrentEntry(limit)
                totalUncompressed += payload.size
                if (totalUncompressed > MAX_TOTAL_UNCOMPRESSED_BYTES) {
                    invalid(DouGridArchiveError.SIZE_LIMIT_EXCEEDED, "Archive expands beyond the allowed size")
                }
                contents[name] = payload
                zip.closeEntry()
                if (contents.size > allowedEntries.size) {
                    invalid(DouGridArchiveError.INVALID_FORMAT, "Archive contains too many entries")
                }
            }
        }

        val manifest = contents[MANIFEST_ENTRY]?.decodeJsonObject(MANIFEST_ENTRY)
            ?: invalid(DouGridArchiveError.INVALID_FORMAT, "Archive manifest is missing")
        if (manifest.strictString("schema", 128) != ARCHIVE_SCHEMA) {
            invalid(DouGridArchiveError.INVALID_FORMAT, "Archive schema is not recognized")
        }
        val archiveVersion = manifest.strictInt("archiveVersion")
        if (archiveVersion != ARCHIVE_VERSION) {
            invalid(
                DouGridArchiveError.UNSUPPORTED_VERSION,
                "Archive version $archiveVersion is not supported",
            )
        }
        val exportedAt = manifest.strictLong("exportedAt", 0L..Long.MAX_VALUE)
        val declaredEntries = manifest.strictStringSet("entries", allowedEntries.size)
        if (declaredEntries != contents.keys) {
            invalid(DouGridArchiveError.INVALID_FORMAT, "Archive entry list does not match its manifest")
        }
        if (!contents.keys.containsAll(requiredEntries)) {
            invalid(DouGridArchiveError.INVALID_FORMAT, "Archive is missing a required entry")
        }

        val projectJson = contents.getValue(PROJECT_ENTRY).decodeJsonObject(PROJECT_ENTRY)
        val palette = decodePalette(contents.getValue(PALETTE_ENTRY).decodeJsonObject(PALETTE_ENTRY))
        val projectFields = decodeProjectFields(projectJson)
        if (projectFields.paletteId != palette.id) {
            invalid(DouGridArchiveError.INVALID_PROJECT, "Project and palette snapshot IDs do not match")
        }
        val expectedCellsBytes = projectFields.width * projectFields.height * Int.SIZE_BYTES
        val cellPayload = contents.getValue(CELLS_ENTRY)
        if (cellPayload.size != expectedCellsBytes) {
            invalid(DouGridArchiveError.INVALID_PROJECT, "Grid cell payload has an unexpected size")
        }
        val cellsBuffer = ByteBuffer.wrap(cellPayload).order(ByteOrder.LITTLE_ENDIAN)
        val cells = IntArray(projectFields.width * projectFields.height) { cellsBuffer.int }
        if (cells.any { it != EMPTY_CELL && it !in palette.colors.indices }) {
            invalid(DouGridArchiveError.INVALID_PROJECT, "Grid contains an unknown palette index")
        }
        val completed = contents.getValue(COMPLETED_ENTRY)
        if (completed.size != cells.size || completed.any { it.toInt() !in 0..1 }) {
            invalid(DouGridArchiveError.INVALID_PROJECT, "Grid completion payload is invalid")
        }
        val rawCompletionMarks = completed.count { it.toInt() != 0 }

        val grid = PatternGrid(projectFields.width, projectFields.height, cells, completed)
        val project = projectFields.toProject(grid)
        val decodedCraft = decodeCraft(contents.getValue(CRAFT_ENTRY).decodeJsonObject(CRAFT_ENTRY))
        if (decodedCraft.completionMarks != rawCompletionMarks) {
            invalid(DouGridArchiveError.INVALID_CRAFT_METADATA, "Craft completion count does not match the grid")
        }
        val craft = decodedCraft.copy(
            completionMarks = project.grid.completedCount(),
        )
        validateCraftMetadata(project, palette, craft)
        val reference = contents[REFERENCE_ENTRY]?.let(::validateAndNormalizeReferencePng)
        validateProject(project, palette)

        return DouGridProjectArchive(
            project = project,
            paletteSnapshot = palette,
            craftMetadata = craft,
            referencePng = reference,
            archiveVersion = archiveVersion,
            exportedAt = exportedAt,
        )
    }

    private fun encodeProject(project: BeadProject) = JSONObject().apply {
        put("schema", PROJECT_SCHEMA)
        put("version", PAYLOAD_VERSION)
        put("id", project.id)
        put("title", project.title)
        put("paletteId", project.paletteId)
        put("width", project.grid.width)
        put("height", project.grid.height)
        put("sourceMode", project.sourceMode.name)
        put("createdAt", project.createdAt)
        put("modifiedAt", project.modifiedAt)
        put("status", project.status.name)
        put("favorite", project.favorite)
        put("inventoryDeducted", project.inventoryDeducted)
        put("boardSize", project.boardSize)
        put("craftElapsedSeconds", project.craftElapsedSeconds)
        put("lastCraftBoardIndex", project.lastCraftBoardIndex)
        put("tags", JSONArray().apply { project.tags.forEach(::put) })
        project.folder?.let { put("folder", it) }
    }

    private fun decodeProjectFields(root: JSONObject): ProjectFields {
        requirePayload(root, PROJECT_SCHEMA, DouGridArchiveError.INVALID_PROJECT)
        val width = root.strictInt("width", 1..512, DouGridArchiveError.INVALID_PROJECT)
        val height = root.strictInt("height", 1..512, DouGridArchiveError.INVALID_PROJECT)
        val tagsArray = root.strictArray("tags", DouGridArchiveError.INVALID_PROJECT)
        if (tagsArray.length() > 12) invalid(DouGridArchiveError.INVALID_PROJECT, "Project has too many tags")
        val tags = ArrayList<String>(tagsArray.length())
        for (index in 0 until tagsArray.length()) {
            val tag = tagsArray.strictString(index, 40, DouGridArchiveError.INVALID_PROJECT)
            if (tag.isBlank() || !tags.addIfAbsent(tag)) {
                invalid(DouGridArchiveError.INVALID_PROJECT, "Project tags are invalid")
            }
        }
        return ProjectFields(
            id = root.strictString("id", 128, DouGridArchiveError.INVALID_PROJECT),
            title = root.strictString("title", 512, DouGridArchiveError.INVALID_PROJECT, allowBlank = true),
            paletteId = root.strictString("paletteId", 128, DouGridArchiveError.INVALID_PROJECT),
            width = width,
            height = height,
            sourceMode = root.strictEnum("sourceMode", DouGridArchiveError.INVALID_PROJECT),
            createdAt = root.strictLong("createdAt", 0L..Long.MAX_VALUE, DouGridArchiveError.INVALID_PROJECT),
            modifiedAt = root.strictLong("modifiedAt", 0L..Long.MAX_VALUE, DouGridArchiveError.INVALID_PROJECT),
            status = root.strictEnum("status", DouGridArchiveError.INVALID_PROJECT),
            favorite = root.strictBoolean("favorite", DouGridArchiveError.INVALID_PROJECT),
            inventoryDeducted = root.strictBoolean("inventoryDeducted", DouGridArchiveError.INVALID_PROJECT),
            boardSize = root.strictInt(
                "boardSize",
                BeadProject.MIN_BOARD_SIZE..BeadProject.MAX_BOARD_SIZE,
                DouGridArchiveError.INVALID_PROJECT,
            ),
            craftElapsedSeconds = root.strictLong(
                "craftElapsedSeconds",
                0L..MAX_ELAPSED_SECONDS,
                DouGridArchiveError.INVALID_PROJECT,
            ),
            lastCraftBoardIndex = root.strictInt(
                "lastCraftBoardIndex",
                0..Int.MAX_VALUE,
                DouGridArchiveError.INVALID_PROJECT,
            ),
            tags = tags,
            folder = root.optionalString("folder", 120, DouGridArchiveError.INVALID_PROJECT),
        )
    }

    private fun encodePalette(palette: BeadPalette) = JSONObject().apply {
        put("schema", PALETTE_SCHEMA)
        put("version", PAYLOAD_VERSION)
        put("id", palette.id)
        put("title", palette.title)
        put("paletteVersion", palette.version)
        put("source", palette.source)
        put("colors", JSONArray().apply {
            palette.colors.forEach { color ->
                put(JSONObject().apply {
                    put("code", color.code)
                    put("argb", String.format(Locale.ROOT, "%08X", color.argb))
                    put("group", color.group)
                    put("name", color.name)
                })
            }
        })
    }

    private fun decodePalette(root: JSONObject): BeadPalette {
        requirePayload(root, PALETTE_SCHEMA, DouGridArchiveError.INVALID_PALETTE)
        val colorsJson = root.strictArray("colors", DouGridArchiveError.INVALID_PALETTE)
        if (colorsJson.length() !in 1..MAX_PALETTE_COLORS) {
            invalid(DouGridArchiveError.INVALID_PALETTE, "Palette color count is invalid")
        }
        val colors = ArrayList<PaletteColor>(colorsJson.length())
        val codes = hashSetOf<String>()
        for (index in 0 until colorsJson.length()) {
            val item = colorsJson.strictObject(index, DouGridArchiveError.INVALID_PALETTE)
            val code = item.strictString("code", 64, DouGridArchiveError.INVALID_PALETTE)
            if (!codes.add(code.uppercase(Locale.ROOT))) {
                invalid(DouGridArchiveError.INVALID_PALETTE, "Palette contains duplicate color code '$code'")
            }
            val hex = item.strictString("argb", 8, DouGridArchiveError.INVALID_PALETTE)
            if (!Regex("[0-9A-Fa-f]{8}").matches(hex)) {
                invalid(DouGridArchiveError.INVALID_PALETTE, "Palette ARGB value is invalid")
            }
            colors += PaletteColor(
                code = code,
                argb = hex.toLong(16).toInt(),
                group = item.strictString("group", 128, DouGridArchiveError.INVALID_PALETTE, allowBlank = true),
                name = item.strictString("name", 256, DouGridArchiveError.INVALID_PALETTE),
            )
        }
        return BeadPalette(
            id = root.strictString("id", 128, DouGridArchiveError.INVALID_PALETTE),
            title = root.strictString("title", 256, DouGridArchiveError.INVALID_PALETTE),
            colors = colors,
            version = root.strictString("paletteVersion", 256, DouGridArchiveError.INVALID_PALETTE),
            source = root.strictString("source", 4_096, DouGridArchiveError.INVALID_PALETTE, allowBlank = true),
        )
    }

    private fun encodeCraft(craft: DouGridCraftMetadata) = JSONObject().apply {
        put("schema", CRAFT_SCHEMA)
        put("version", PAYLOAD_VERSION)
        put("boardSize", craft.boardSize)
        put("elapsedSeconds", craft.elapsedSeconds)
        put("lastBoardIndex", craft.lastBoardIndex)
        put("completionMarks", craft.completionMarks)
        craft.highlightedColorCode?.let { put("highlightedColorCode", it) }
        put("hideCompleted", craft.hideCompleted)
    }

    private fun decodeCraft(root: JSONObject): DouGridCraftMetadata {
        requirePayload(root, CRAFT_SCHEMA, DouGridArchiveError.INVALID_CRAFT_METADATA)
        return DouGridCraftMetadata(
            boardSize = root.strictInt(
                "boardSize",
                BeadProject.MIN_BOARD_SIZE..BeadProject.MAX_BOARD_SIZE,
                DouGridArchiveError.INVALID_CRAFT_METADATA,
            ),
            elapsedSeconds = root.strictLong(
                "elapsedSeconds",
                0L..MAX_ELAPSED_SECONDS,
                DouGridArchiveError.INVALID_CRAFT_METADATA,
            ),
            lastBoardIndex = root.strictInt(
                "lastBoardIndex",
                0..Int.MAX_VALUE,
                DouGridArchiveError.INVALID_CRAFT_METADATA,
            ),
            completionMarks = root.strictInt(
                "completionMarks",
                0..512 * 512,
                DouGridArchiveError.INVALID_CRAFT_METADATA,
            ),
            highlightedColorCode = root.optionalString(
                "highlightedColorCode",
                64,
                DouGridArchiveError.INVALID_CRAFT_METADATA,
            ),
            hideCompleted = root.strictBoolean("hideCompleted", DouGridArchiveError.INVALID_CRAFT_METADATA),
        )
    }

    private fun validateProject(project: BeadProject, palette: BeadPalette) {
        if (!Regex("[A-Za-z0-9_-]{1,128}").matches(project.id)) {
            invalid(DouGridArchiveError.INVALID_PROJECT, "Project ID is invalid")
        }
        validateText(project.title, 512, true, DouGridArchiveError.INVALID_PROJECT, "Project title")
        if (project.paletteId != palette.id) {
            invalid(DouGridArchiveError.INVALID_PROJECT, "Project palette ID does not match its snapshot")
        }
        if (project.createdAt < 0 || project.modifiedAt < 0) {
            invalid(DouGridArchiveError.INVALID_PROJECT, "Project timestamp is invalid")
        }
        if (project.boardSize !in BeadProject.MIN_BOARD_SIZE..BeadProject.MAX_BOARD_SIZE) {
            invalid(DouGridArchiveError.INVALID_PROJECT, "Project board size is invalid")
        }
        if (project.craftElapsedSeconds !in 0L..MAX_ELAPSED_SECONDS) {
            invalid(DouGridArchiveError.INVALID_PROJECT, "Project craft duration is invalid")
        }
        if (project.lastCraftBoardIndex !in 0 until project.boardCount) {
            invalid(DouGridArchiveError.INVALID_PROJECT, "Project craft board position is invalid")
        }
        if (project.grid.cells.any { it != EMPTY_CELL && it !in palette.colors.indices }) {
            invalid(DouGridArchiveError.INVALID_PROJECT, "Project grid contains an unknown palette index")
        }
        if (project.grid.completed.any { it.toInt() !in 0..1 }) {
            invalid(DouGridArchiveError.INVALID_PROJECT, "Project completion data is invalid")
        }
        if (project.tags.size > 12 || project.tags.distinct().size != project.tags.size ||
            project.tags.any { it.isBlank() || it.length > 40 || it.any(Char::isISOControl) }
        ) {
            invalid(DouGridArchiveError.INVALID_PROJECT, "Project tags are invalid")
        }
        project.folder?.let {
            validateText(it, 120, false, DouGridArchiveError.INVALID_PROJECT, "Project folder")
        }
    }

    private fun validatePalette(palette: BeadPalette) {
        if (!Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}").matches(palette.id)) {
            invalid(DouGridArchiveError.INVALID_PALETTE, "Palette ID is invalid")
        }
        validateText(palette.title, 256, false, DouGridArchiveError.INVALID_PALETTE, "Palette title")
        validateText(palette.version, 256, false, DouGridArchiveError.INVALID_PALETTE, "Palette version")
        validateText(palette.source, 4_096, true, DouGridArchiveError.INVALID_PALETTE, "Palette source")
        if (palette.colors.size !in 1..MAX_PALETTE_COLORS) {
            invalid(DouGridArchiveError.INVALID_PALETTE, "Palette color count is invalid")
        }
        val codes = hashSetOf<String>()
        palette.colors.forEach { color ->
            validateText(color.code, 64, false, DouGridArchiveError.INVALID_PALETTE, "Color code")
            validateText(color.name, 256, false, DouGridArchiveError.INVALID_PALETTE, "Color name")
            validateText(color.group, 128, true, DouGridArchiveError.INVALID_PALETTE, "Color group")
            if (!codes.add(color.code.uppercase(Locale.ROOT))) {
                invalid(DouGridArchiveError.INVALID_PALETTE, "Palette color codes must be unique")
            }
        }
    }

    private fun validateCraftMetadata(
        project: BeadProject,
        palette: BeadPalette,
        craft: DouGridCraftMetadata,
    ) {
        if (craft.boardSize != project.boardSize || craft.elapsedSeconds != project.craftElapsedSeconds ||
            craft.lastBoardIndex != project.lastCraftBoardIndex ||
            craft.completionMarks != project.grid.completedCount()
        ) {
            invalid(DouGridArchiveError.INVALID_CRAFT_METADATA, "Craft metadata does not match the project")
        }
        craft.highlightedColorCode?.let { highlighted ->
            if (palette.colors.none { it.code == highlighted }) {
                invalid(DouGridArchiveError.INVALID_CRAFT_METADATA, "Highlighted color is not in the palette")
            }
        }
    }

    private fun validateAndNormalizeReferencePng(bytes: ByteArray): ByteArray {
        if (bytes.size !in 33..MAX_REFERENCE_BYTES) {
            invalid(DouGridArchiveError.INVALID_REFERENCE_IMAGE, "Reference PNG size is invalid")
        }
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        if (!bytes.copyOfRange(0, signature.size).contentEquals(signature)) {
            invalid(DouGridArchiveError.INVALID_REFERENCE_IMAGE, "Reference image is not a PNG")
        }

        var offset = signature.size
        var chunks = 0
        var sawHeader = false
        var sawImageData = false
        var sawEnd = false
        while (offset < bytes.size) {
            if (bytes.size - offset < 12) {
                invalid(DouGridArchiveError.INVALID_REFERENCE_IMAGE, "Reference PNG is truncated")
            }
            val length = bytes.readUnsignedInt(offset)
            if (length > Int.MAX_VALUE || length + 12 > bytes.size - offset) {
                invalid(DouGridArchiveError.INVALID_REFERENCE_IMAGE, "Reference PNG chunk is invalid")
            }
            val dataLength = length.toInt()
            val typeOffset = offset + 4
            val dataOffset = offset + 8
            val type = String(bytes, typeOffset, 4, StandardCharsets.US_ASCII)
            if (!type.all { it in 'A'..'Z' || it in 'a'..'z' }) {
                invalid(DouGridArchiveError.INVALID_REFERENCE_IMAGE, "Reference PNG chunk type is invalid")
            }
            val expectedCrc = bytes.readUnsignedInt(dataOffset + dataLength)
            val crc = CRC32().apply { update(bytes, typeOffset, dataLength + 4) }.value
            if (crc != expectedCrc) {
                invalid(DouGridArchiveError.INVALID_REFERENCE_IMAGE, "Reference PNG checksum is invalid")
            }
            if (chunks == 0) {
                if (type != "IHDR" || dataLength != 13) {
                    invalid(DouGridArchiveError.INVALID_REFERENCE_IMAGE, "Reference PNG header is invalid")
                }
                val width = bytes.readUnsignedInt(dataOffset)
                val height = bytes.readUnsignedInt(dataOffset + 4)
                if (width !in 1..MAX_PNG_DIMENSION.toLong() || height !in 1..MAX_PNG_DIMENSION.toLong() ||
                    width * height > MAX_PNG_PIXELS
                ) {
                    invalid(DouGridArchiveError.INVALID_REFERENCE_IMAGE, "Reference PNG dimensions are too large")
                }
                sawHeader = true
            } else if (type == "IHDR") {
                invalid(DouGridArchiveError.INVALID_REFERENCE_IMAGE, "Reference PNG contains multiple headers")
            }
            if (type == "IDAT") sawImageData = true
            offset += dataLength + 12
            chunks++
            if (chunks > 10_000) {
                invalid(DouGridArchiveError.INVALID_REFERENCE_IMAGE, "Reference PNG contains too many chunks")
            }
            if (type == "IEND") {
                if (dataLength != 0 || offset != bytes.size) {
                    invalid(DouGridArchiveError.INVALID_REFERENCE_IMAGE, "Reference PNG ending is invalid")
                }
                sawEnd = true
                break
            }
        }
        if (!sawHeader || !sawImageData || !sawEnd) {
            invalid(DouGridArchiveError.INVALID_REFERENCE_IMAGE, "Reference PNG is incomplete")
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        } catch (error: Exception) {
            invalid(DouGridArchiveError.INVALID_REFERENCE_IMAGE, "Reference PNG cannot be decoded", error)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            invalid(DouGridArchiveError.INVALID_REFERENCE_IMAGE, "Reference PNG cannot be decoded")
        }
        var sampleSize = 1
        while (
            ((bounds.outWidth + sampleSize - 1L) / sampleSize) *
            ((bounds.outHeight + sampleSize - 1L) / sampleSize) > MAX_REFERENCE_DECODE_PIXELS
        ) {
            sampleSize *= 2
        }
        val bitmap = try {
            BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sampleSize },
            ) ?: invalid(DouGridArchiveError.INVALID_REFERENCE_IMAGE, "Reference PNG cannot be decoded")
        } catch (error: OutOfMemoryError) {
            invalid(DouGridArchiveError.INVALID_REFERENCE_IMAGE, "Reference PNG needs too much memory", error)
        } catch (error: Exception) {
            invalid(DouGridArchiveError.INVALID_REFERENCE_IMAGE, "Reference PNG cannot be decoded", error)
        }
        return try {
            if (bitmap.width.toLong() * bitmap.height <= MAX_REFERENCE_DECODE_PIXELS && sampleSize == 1) {
                bytes
            } else {
                ByteArrayOutputStream().use { output ->
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                        invalid(DouGridArchiveError.INVALID_REFERENCE_IMAGE, "Reference PNG cannot be normalized")
                    }
                    output.toByteArray().also { normalized ->
                        if (normalized.size !in 33..MAX_REFERENCE_BYTES) {
                            invalid(DouGridArchiveError.INVALID_REFERENCE_IMAGE, "Normalized reference PNG is too large")
                        }
                    }
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun requirePayload(root: JSONObject, schema: String, error: DouGridArchiveError) {
        if (root.strictString("schema", 128, error) != schema) invalid(error, "Payload schema is not recognized")
        val version = root.strictInt("version", error = error)
        if (version != PAYLOAD_VERSION) {
            invalid(DouGridArchiveError.UNSUPPORTED_VERSION, "Payload version $version is not supported")
        }
    }

    private fun validateEntryPath(name: String) {
        if (name.isBlank() || name.startsWith('/') || name.contains('\\') ||
            name.split('/').any { it.isBlank() || it == "." || it == ".." }
        ) {
            invalid(DouGridArchiveError.INVALID_FORMAT, "Unsafe archive entry path")
        }
    }

    private fun validateText(
        value: String,
        maxLength: Int,
        allowBlank: Boolean,
        error: DouGridArchiveError,
        label: String,
    ) {
        if (value.length > maxLength || (!allowBlank && value.isBlank()) || value.any(Char::isISOControl)) {
            invalid(error, "$label is invalid")
        }
    }

    private fun ZipOutputStream.writeEntry(name: String, bytes: ByteArray) {
        val entry = ZipEntry(name).apply { time = 0L }
        putNextEntry(entry)
        write(bytes)
        closeEntry()
    }

    private fun ZipInputStream.readCurrentEntry(limit: Int): ByteArray {
        val target = ByteArrayOutputStream(minOf(limit, 16 * 1024))
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) {
                invalid(DouGridArchiveError.SIZE_LIMIT_EXCEEDED, "Archive entry exceeds its size limit")
            }
            target.write(buffer, 0, count)
        }
        return target.toByteArray()
    }

    private fun ByteArray.decodeJsonObject(entryName: String): JSONObject {
        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(this))
                .toString()
                .removePrefix("\uFEFF")
        } catch (error: Exception) {
            invalid(DouGridArchiveError.INVALID_FORMAT, "Archive entry '$entryName' is not valid UTF-8", error)
        }
        return try {
            JSONObject(text)
        } catch (error: JSONException) {
            invalid(DouGridArchiveError.INVALID_FORMAT, "Archive entry '$entryName' is not valid JSON", error)
        }
    }

    private fun JSONObject.strictString(
        key: String,
        maxLength: Int,
        error: DouGridArchiveError = DouGridArchiveError.INVALID_FORMAT,
        allowBlank: Boolean = false,
    ): String {
        val value = opt(key) as? String ?: invalid(error, "JSON field '$key' must be a string")
        validateText(value, maxLength, allowBlank, error, "JSON field '$key'")
        return value
    }

    private fun JSONObject.optionalString(key: String, maxLength: Int, error: DouGridArchiveError): String? {
        val raw = opt(key)
        if (raw == null || raw == JSONObject.NULL) return null
        val value = raw as? String ?: invalid(error, "JSON field '$key' must be a string or null")
        validateText(value, maxLength, false, error, "JSON field '$key'")
        return value
    }

    private fun JSONObject.strictInt(
        key: String,
        range: IntRange = Int.MIN_VALUE..Int.MAX_VALUE,
        error: DouGridArchiveError = DouGridArchiveError.INVALID_FORMAT,
    ): Int {
        val raw = opt(key) as? Number ?: invalid(error, "JSON field '$key' must be an integer")
        val value = raw.toString().toIntOrNull() ?: invalid(error, "JSON field '$key' must be an integer")
        if (value !in range) invalid(error, "JSON field '$key' is out of range")
        return value
    }

    private fun JSONObject.strictLong(
        key: String,
        range: LongRange,
        error: DouGridArchiveError = DouGridArchiveError.INVALID_FORMAT,
    ): Long {
        val raw = opt(key) as? Number ?: invalid(error, "JSON field '$key' must be an integer")
        val value = raw.toString().toLongOrNull() ?: invalid(error, "JSON field '$key' must be an integer")
        if (value !in range) invalid(error, "JSON field '$key' is out of range")
        return value
    }

    private fun JSONObject.strictBoolean(key: String, error: DouGridArchiveError): Boolean =
        opt(key) as? Boolean ?: invalid(error, "JSON field '$key' must be a boolean")

    private fun JSONObject.strictArray(key: String, error: DouGridArchiveError): JSONArray =
        opt(key) as? JSONArray ?: invalid(error, "JSON field '$key' must be an array")

    private fun JSONObject.strictStringSet(key: String, maxSize: Int): Set<String> {
        val array = strictArray(key, DouGridArchiveError.INVALID_FORMAT)
        if (array.length() > maxSize) invalid(DouGridArchiveError.INVALID_FORMAT, "Manifest has too many entries")
        val result = linkedSetOf<String>()
        for (index in 0 until array.length()) {
            val name = array.strictString(index, 128, DouGridArchiveError.INVALID_FORMAT)
            validateEntryPath(name)
            if (!result.add(name)) invalid(DouGridArchiveError.INVALID_FORMAT, "Manifest has duplicate entries")
        }
        return result
    }

    private inline fun <reified T : Enum<T>> JSONObject.strictEnum(
        key: String,
        error: DouGridArchiveError,
    ): T {
        val raw = strictString(key, 64, error)
        return enumValues<T>().firstOrNull { it.name == raw }
            ?: invalid(error, "JSON field '$key' has an unsupported value")
    }

    private fun JSONArray.strictString(index: Int, maxLength: Int, error: DouGridArchiveError): String {
        val value = opt(index) as? String ?: invalid(error, "JSON array item must be a string")
        validateText(value, maxLength, true, error, "JSON array item")
        return value
    }

    private fun JSONArray.strictObject(index: Int, error: DouGridArchiveError): JSONObject =
        opt(index) as? JSONObject ?: invalid(error, "JSON array item must be an object")

    private fun <T> MutableList<T>.addIfAbsent(value: T): Boolean {
        if (contains(value)) return false
        add(value)
        return true
    }

    private fun ByteArray.readUnsignedInt(offset: Int): Long =
        ((this[offset].toLong() and 0xFF) shl 24) or
            ((this[offset + 1].toLong() and 0xFF) shl 16) or
            ((this[offset + 2].toLong() and 0xFF) shl 8) or
            (this[offset + 3].toLong() and 0xFF)

    private data class ProjectFields(
        val id: String,
        val title: String,
        val paletteId: String,
        val width: Int,
        val height: Int,
        val sourceMode: ConversionMode,
        val createdAt: Long,
        val modifiedAt: Long,
        val status: ProjectStatus,
        val favorite: Boolean,
        val inventoryDeducted: Boolean,
        val boardSize: Int,
        val craftElapsedSeconds: Long,
        val lastCraftBoardIndex: Int,
        val tags: List<String>,
        val folder: String?,
    ) {
        fun toProject(grid: PatternGrid) = BeadProject(
            id = id,
            title = title,
            paletteId = paletteId,
            grid = grid,
            sourceMode = sourceMode,
            createdAt = createdAt,
            modifiedAt = modifiedAt,
            status = status,
            favorite = favorite,
            inventoryDeducted = inventoryDeducted,
            sourcePath = null,
            boardSize = boardSize,
            craftElapsedSeconds = craftElapsedSeconds,
            lastCraftBoardIndex = lastCraftBoardIndex,
            tags = tags,
            folder = folder,
        )
    }

    private class CountingLimitInputStream(
        private val delegate: InputStream,
        private val limit: Long,
    ) : InputStream() {
        private var count = 0L

        override fun read(): Int = delegate.read().also { value ->
            if (value >= 0) addCount(1)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            delegate.read(buffer, offset, length).also { read ->
                if (read > 0) addCount(read.toLong())
            }

        override fun skip(byteCount: Long): Long = delegate.skip(byteCount).also(::addCount)

        override fun available(): Int = delegate.available()

        override fun close() = Unit

        private fun addCount(delta: Long) {
            count += delta
            if (count > limit) {
                invalid(DouGridArchiveError.SIZE_LIMIT_EXCEEDED, "Archive exceeds the compressed size limit")
            }
        }
    }

    private class NonClosingOutputStream(private val delegate: OutputStream) : OutputStream() {
        override fun write(value: Int) = delegate.write(value)

        override fun write(buffer: ByteArray, offset: Int, length: Int) =
            delegate.write(buffer, offset, length)

        override fun flush() = delegate.flush()

        override fun close() = delegate.flush()
    }

    private fun invalid(error: DouGridArchiveError, message: String, cause: Throwable? = null): Nothing =
        throw DouGridArchiveException(error, message, cause)
}
