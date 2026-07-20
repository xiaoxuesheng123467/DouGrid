package com.qiao.dougrid.data

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

enum class InventoryImportMode { MERGE, REPLACE }

enum class InventoryCsvIssueCode {
    EMPTY_FILE,
    FILE_TOO_LARGE,
    MALFORMED_CSV,
    INVALID_HEADER,
    WRONG_COLUMN_COUNT,
    UNSUPPORTED_VERSION,
    INVALID_PALETTE_ID,
    INVALID_COLOR_CODE,
    INVALID_ON_HAND,
    INVALID_BAG_SIZE,
    DUPLICATE_ENTRY,
}

data class InventoryCsvIssue(
    val line: Int,
    val column: String? = null,
    val code: InventoryCsvIssueCode,
    val message: String,
)

data class InventoryCsvImportResult(
    val inventory: List<InventoryEntry>,
    val importedCount: Int,
    val mode: InventoryImportMode,
    val issues: List<InventoryCsvIssue> = emptyList(),
) {
    val applied: Boolean get() = issues.isEmpty()
}

/**
 * UTF-8 CSV interchange for the local inventory. Imports are atomic: when any
 * row is invalid, [InventoryCsvImportResult.inventory] remains unchanged.
 */
object InventoryCsvCodec {
    const val MIME_TYPE = "text/csv"
    const val FORMAT_VERSION = "1"
    const val MAX_ROWS = 10_000
    const val MAX_INPUT_BYTES = 4 * 1024 * 1024
    const val MAX_QUANTITY = 999_999
    const val MAX_BAG_SIZE = 999_999

    private val header = listOf(
        "dougrid_inventory_version",
        "palette_id",
        "color_code",
        "on_hand",
        "bag_size",
    )
    private val paletteIdPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

    /** Encodes a deterministic CSV document with every field quoted. */
    fun encode(inventory: Collection<InventoryEntry>): String {
        require(inventory.size <= MAX_ROWS) { "Inventory exceeds the CSV row limit" }
        val entries = inventory.sortedWith(compareBy(InventoryEntry::paletteId, InventoryEntry::colorCode))
        val seen = hashSetOf<String>()
        entries.forEachIndexed { index, entry ->
            requireValidEntry(entry, index + 2)
            require(seen.add(entry.key)) { "Duplicate inventory entry '${entry.key}'" }
        }

        return buildString {
            appendCsvRow(header)
            entries.forEach { entry ->
                appendCsvRow(
                    listOf(
                        FORMAT_VERSION,
                        entry.paletteId,
                        entry.colorCode,
                        entry.onHand.toString(),
                        entry.bagSize.toString(),
                    ),
                )
            }
        }
    }

    /** Writes UTF-8 without closing [output]. */
    fun write(output: OutputStream, inventory: Collection<InventoryEntry>) {
        output.write(encode(inventory).toByteArray(StandardCharsets.UTF_8))
        output.flush()
    }

    /** Reads a bounded UTF-8 document without closing [input]. */
    fun read(
        input: InputStream,
        existing: List<InventoryEntry> = emptyList(),
        mode: InventoryImportMode = InventoryImportMode.MERGE,
    ): InventoryCsvImportResult {
        val bytes = try {
            input.readBytesBounded(MAX_INPUT_BYTES)
        } catch (_: InputLimitExceededException) {
            return failed(
                existing,
                mode,
                InventoryCsvIssue(
                    line = 1,
                    code = InventoryCsvIssueCode.FILE_TOO_LARGE,
                    message = "CSV exceeds the ${MAX_INPUT_BYTES / 1024 / 1024} MB limit",
                ),
            )
        }
        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: Exception) {
            return failed(
                existing,
                mode,
                InventoryCsvIssue(
                    line = 1,
                    code = InventoryCsvIssueCode.MALFORMED_CSV,
                    message = "CSV is not valid UTF-8",
                ),
            )
        }
        return decode(text, existing, mode)
    }

    fun decode(
        csv: String,
        existing: List<InventoryEntry> = emptyList(),
        mode: InventoryImportMode = InventoryImportMode.MERGE,
    ): InventoryCsvImportResult {
        if (csv.toByteArray(StandardCharsets.UTF_8).size > MAX_INPUT_BYTES) {
            return failed(
                existing,
                mode,
                InventoryCsvIssue(
                    line = 1,
                    code = InventoryCsvIssueCode.FILE_TOO_LARGE,
                    message = "CSV exceeds the ${MAX_INPUT_BYTES / 1024 / 1024} MB limit",
                ),
            )
        }

        val parsed = parseCsv(csv.removePrefix("\uFEFF"))
        parsed.issue?.let { return failed(existing, mode, it) }
        val rows = parsed.rows.filterNot { row -> row.fields.all(String::isBlank) }
        if (rows.isEmpty()) {
            return failed(
                existing,
                mode,
                InventoryCsvIssue(1, code = InventoryCsvIssueCode.EMPTY_FILE, message = "CSV is empty"),
            )
        }
        if (rows.first().fields != header) {
            return failed(
                existing,
                mode,
                InventoryCsvIssue(
                    line = rows.first().line,
                    code = InventoryCsvIssueCode.INVALID_HEADER,
                    message = "CSV header does not match the DouGrid inventory format",
                ),
            )
        }
        if (rows.size - 1 > MAX_ROWS) {
            return failed(
                existing,
                mode,
                InventoryCsvIssue(
                    line = rows[MAX_ROWS + 1].line,
                    code = InventoryCsvIssueCode.FILE_TOO_LARGE,
                    message = "CSV exceeds the $MAX_ROWS row limit",
                ),
            )
        }

        val issues = mutableListOf<InventoryCsvIssue>()
        val imported = ArrayList<InventoryEntry>(rows.size - 1)
        val seen = hashSetOf<String>()
        for (row in rows.drop(1)) {
            if (row.fields.size != header.size) {
                issues += InventoryCsvIssue(
                    row.line,
                    code = InventoryCsvIssueCode.WRONG_COLUMN_COUNT,
                    message = "Expected ${header.size} columns, found ${row.fields.size}",
                )
                continue
            }
            val version = row.fields[0].trim()
            val paletteId = row.fields[1].trim()
            val colorCode = row.fields[2].trim()
            val onHand = row.fields[3].trim().toIntOrNull()
            val bagSize = row.fields[4].trim().toIntOrNull()

            if (version != FORMAT_VERSION) {
                issues += InventoryCsvIssue(
                    row.line,
                    header[0],
                    InventoryCsvIssueCode.UNSUPPORTED_VERSION,
                    "Unsupported inventory CSV version '$version'",
                )
            }
            if (!paletteIdPattern.matches(paletteId)) {
                issues += InventoryCsvIssue(
                    row.line,
                    header[1],
                    InventoryCsvIssueCode.INVALID_PALETTE_ID,
                    "Palette ID must use 1-128 letters, digits, dots, underscores, or hyphens",
                )
            }
            if (!isValidColorCode(colorCode)) {
                issues += InventoryCsvIssue(
                    row.line,
                    header[2],
                    InventoryCsvIssueCode.INVALID_COLOR_CODE,
                    "Color code must be 1-64 printable characters and cannot start with a formula marker",
                )
            }
            if (onHand == null || onHand !in 0..MAX_QUANTITY) {
                issues += InventoryCsvIssue(
                    row.line,
                    header[3],
                    InventoryCsvIssueCode.INVALID_ON_HAND,
                    "On-hand quantity must be between 0 and $MAX_QUANTITY",
                )
            }
            if (bagSize == null || bagSize !in 1..MAX_BAG_SIZE) {
                issues += InventoryCsvIssue(
                    row.line,
                    header[4],
                    InventoryCsvIssueCode.INVALID_BAG_SIZE,
                    "Bag size must be between 1 and $MAX_BAG_SIZE",
                )
            }
            if (version != FORMAT_VERSION || !paletteIdPattern.matches(paletteId) ||
                !isValidColorCode(colorCode) || onHand == null || onHand !in 0..MAX_QUANTITY ||
                bagSize == null || bagSize !in 1..MAX_BAG_SIZE
            ) {
                continue
            }

            val entry = InventoryEntry(paletteId, colorCode, onHand, bagSize)
            if (!seen.add(entry.key)) {
                issues += InventoryCsvIssue(
                    row.line,
                    header[2],
                    InventoryCsvIssueCode.DUPLICATE_ENTRY,
                    "Duplicate inventory entry '${entry.key}'",
                )
            } else {
                imported += entry
            }
        }

        if (issues.isNotEmpty()) {
            return InventoryCsvImportResult(existing.toList(), 0, mode, issues)
        }
        val result = when (mode) {
            InventoryImportMode.REPLACE -> imported
            InventoryImportMode.MERGE -> LinkedHashMap<String, InventoryEntry>().apply {
                existing.forEach { put(it.key, it) }
                imported.forEach { put(it.key, it) }
            }.values.toList()
        }
        return InventoryCsvImportResult(result, imported.size, mode)
    }

    private fun requireValidEntry(entry: InventoryEntry, line: Int) {
        require(paletteIdPattern.matches(entry.paletteId)) { "Invalid palette ID at CSV line $line" }
        require(isValidColorCode(entry.colorCode)) { "Invalid color code at CSV line $line" }
        require(entry.onHand in 0..MAX_QUANTITY) { "Invalid on-hand quantity at CSV line $line" }
        require(entry.bagSize in 1..MAX_BAG_SIZE) { "Invalid bag size at CSV line $line" }
    }

    private fun isValidColorCode(value: String): Boolean =
        value.isNotBlank() && value.length <= 64 && value.none(Char::isISOControl) &&
            value.first() !in charArrayOf('=', '+', '-', '@')

    private fun failed(
        existing: List<InventoryEntry>,
        mode: InventoryImportMode,
        issue: InventoryCsvIssue,
    ) = InventoryCsvImportResult(existing.toList(), 0, mode, listOf(issue))

    private fun StringBuilder.appendCsvRow(fields: List<String>) {
        fields.forEachIndexed { index, field ->
            if (index > 0) append(',')
            append('"')
            field.forEach { character ->
                if (character == '"') append("\"\"") else append(character)
            }
            append('"')
        }
        append("\r\n")
    }

    private data class ParsedRow(val line: Int, val fields: List<String>)
    private data class CsvParseResult(
        val rows: List<ParsedRow> = emptyList(),
        val issue: InventoryCsvIssue? = null,
    )

    private fun parseCsv(csv: String): CsvParseResult {
        val rows = mutableListOf<ParsedRow>()
        val fields = mutableListOf<String>()
        val field = StringBuilder()
        var line = 1
        var rowLine = 1
        var index = 0
        var fieldStarted = false
        var inQuotes = false
        var quoteClosed = false

        fun syntax(message: String) = CsvParseResult(
            issue = InventoryCsvIssue(
                line,
                code = InventoryCsvIssueCode.MALFORMED_CSV,
                message = message,
            ),
        )
        fun finishField() {
            fields += field.toString()
            field.setLength(0)
            fieldStarted = false
            quoteClosed = false
        }
        fun finishRow() {
            finishField()
            rows += ParsedRow(rowLine, fields.toList())
            fields.clear()
            rowLine = line + 1
        }
        fun append(character: Char): CsvParseResult? {
            if (field.length >= 4_096) return syntax("CSV field exceeds the 4096 character limit")
            field.append(character)
            fieldStarted = true
            return null
        }
        fun limitsIssue(): CsvParseResult? = when {
            rows.size > MAX_ROWS + 1 -> CsvParseResult(
                issue = InventoryCsvIssue(
                    line,
                    code = InventoryCsvIssueCode.FILE_TOO_LARGE,
                    message = "CSV exceeds the $MAX_ROWS row limit",
                ),
            )
            fields.size > 64 -> syntax("CSV row contains too many columns")
            else -> null
        }

        while (index < csv.length) {
            val character = csv[index]
            val isNewline = character == '\n' || character == '\r'
            if (inQuotes) {
                when {
                    character == '"' && index + 1 < csv.length && csv[index + 1] == '"' -> {
                        append('"')?.let { return it }
                        index++
                    }
                    character == '"' -> {
                        inQuotes = false
                        quoteClosed = true
                    }
                    isNewline -> {
                        append('\n')?.let { return it }
                        if (character == '\r' && index + 1 < csv.length && csv[index + 1] == '\n') index++
                        line++
                    }
                    else -> append(character)?.let { return it }
                }
            } else if (quoteClosed) {
                when {
                    character == ',' -> {
                        finishField()
                        limitsIssue()?.let { return it }
                    }
                    isNewline -> {
                        finishRow()
                        limitsIssue()?.let { return it }
                        if (character == '\r' && index + 1 < csv.length && csv[index + 1] == '\n') index++
                        line++
                        rowLine = line
                    }
                    else -> return syntax("Unexpected character after closing quote")
                }
            } else {
                when {
                    character == '"' && !fieldStarted -> {
                        inQuotes = true
                        fieldStarted = true
                    }
                    character == '"' -> return syntax("Quote inside an unquoted CSV field")
                    character == ',' -> {
                        finishField()
                        limitsIssue()?.let { return it }
                    }
                    isNewline -> {
                        finishRow()
                        limitsIssue()?.let { return it }
                        if (character == '\r' && index + 1 < csv.length && csv[index + 1] == '\n') index++
                        line++
                        rowLine = line
                    }
                    else -> append(character)?.let { return it }
                }
            }
            index++
        }

        if (inQuotes) return syntax("Unterminated quoted CSV field")
        if (fieldStarted || quoteClosed || field.isNotEmpty() || fields.isNotEmpty()) finishRow()
        limitsIssue()?.let { return it }
        return CsvParseResult(rows)
    }

    private fun InputStream.readBytesBounded(limit: Int): ByteArray {
        val target = ByteArrayOutputStream(minOf(limit, 16 * 1024))
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) throw InputLimitExceededException()
            target.write(buffer, 0, count)
        }
        return target.toByteArray()
    }

    private class InputLimitExceededException : Exception()
}
