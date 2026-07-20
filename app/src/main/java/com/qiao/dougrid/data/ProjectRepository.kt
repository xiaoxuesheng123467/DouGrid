package com.qiao.dougrid.data

import android.content.Context
import android.graphics.Bitmap
import android.util.AtomicFile
import android.util.Base64
import android.util.Log
import com.qiao.dougrid.core.ConversionMode
import com.qiao.dougrid.core.PatternGrid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

data class ProjectRepositoryLoadIssue(
    val section: String,
    val index: Int,
    val reason: String,
)

class ProjectRepositoryLoadException(
    message: String,
    cause: Throwable,
) : IOException(message, cause)

class ProjectRepositorySaveBlockedException(message: String) : IllegalStateException(message)

class ProjectRepository(context: Context) {
    private val statePath = File(context.filesDir, "dougrid-state-v1.json")
    private val stateFile = AtomicFile(statePath)
    private val sourceDirectory = File(context.filesDir, "project-sources").apply { mkdirs() }.canonicalFile
    private val loadStateLock = Any()

    @Volatile
    private var saveBlockedByLoadFailure = false

    @Volatile
    private var preservedRecords = PreservedRecords()

    @Volatile
    var loadIssues: List<ProjectRepositoryLoadIssue> = emptyList()
        private set

    suspend fun load(
        validateProject: ((BeadProject) -> String?)? = null,
        validateInventory: ((InventoryEntry) -> String?)? = null,
    ): PersistedAppState = withContext(Dispatchers.IO) {
        if (!statePath.exists() && !File("${statePath.path}$ATOMIC_BACKUP_SUFFIX").exists()) {
            updateSuccessfulLoad(DecodedState(PersistedAppState(emptyList(), emptyList(), AppSettings())))
            return@withContext PersistedAppState(emptyList(), emptyList(), AppSettings())
        }

        try {
            stateFile.openRead().bufferedReader(Charsets.UTF_8).use { reader ->
                val decoded = decodeState(JSONObject(reader.readText()), validateProject, validateInventory)
                updateSuccessfulLoad(decoded)
                decoded.state
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            synchronized(loadStateLock) {
                saveBlockedByLoadFailure = true
                preservedRecords = PreservedRecords()
                loadIssues = emptyList()
            }
            throw ProjectRepositoryLoadException(
                message = "无法安全读取本地工程数据，已阻止覆盖原文件",
                cause = error,
            )
        }
    }

    suspend fun save(state: PersistedAppState) = withContext(Dispatchers.IO) {
        val records = synchronized(loadStateLock) {
            if (saveBlockedByLoadFailure) {
                throw ProjectRepositorySaveBlockedException("上次读取本地工程失败，拒绝覆盖原文件")
            }
            preservedRecords
        }
        val output = stateFile.startWrite()
        try {
            val writer = output.bufferedWriter(Charsets.UTF_8)
            writer.write(encodeState(state, records).toString())
            writer.flush()
            stateFile.finishWrite(output)
        } catch (error: Throwable) {
            stateFile.failWrite(output)
            throw error
        }
    }

    suspend fun copySource(context: Context, source: android.net.Uri, projectId: String): String? =
        withContext(Dispatchers.IO) {
            val destination = managedProjectFile(projectId, LEGACY_SOURCE_SUFFIX) ?: return@withContext null
            fileOperationOrNull {
                val input = context.contentResolver.openInputStream(source) ?: return@fileOperationOrNull null
                input.use { sourceInput ->
                    writeAtomically(destination) { output -> sourceInput.copyTo(output) }
                }
            }
        }

    suspend fun saveReference(bitmap: Bitmap, projectId: String): String =
        withContext(Dispatchers.IO) {
            val destination = requireNotNull(managedProjectFile(projectId, REFERENCE_SUFFIX)) {
                "工程 ID 不合法"
            }
            writeAtomically(destination) { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "无法编码裁剪参考图"
                }
            }
        }

    suspend fun saveReferencePng(bytes: ByteArray, projectId: String): String =
        withContext(Dispatchers.IO) {
            val destination = requireNotNull(managedProjectFile(projectId, REFERENCE_SUFFIX)) {
                "工程 ID 不合法"
            }
            require(bytes.isNotEmpty()) { "参考图不能为空" }
            writeAtomically(destination) { output -> output.write(bytes) }
        }

    suspend fun copyReference(sourcePath: String, newProjectId: String): String? =
        withContext(Dispatchers.IO) {
            val source = managedReferenceFile(sourcePath) ?: return@withContext null
            val destination = managedProjectFile(newProjectId, REFERENCE_SUFFIX) ?: return@withContext null
            if (source.canonicalFile == destination.canonicalFile) return@withContext destination.absolutePath
            fileOperationOrNull {
                source.inputStream().buffered().use { input ->
                    writeAtomically(destination) { output -> input.copyTo(output) }
                }
            }
        }

    suspend fun deleteProjectReferences(projectId: String): Int = withContext(Dispatchers.IO) {
        deleteProjectReferencesInternal(listOf(projectId))
    }

    suspend fun deleteProjectReferences(projectIds: Collection<String>): Int = withContext(Dispatchers.IO) {
        deleteProjectReferencesInternal(projectIds)
    }

    suspend fun deleteOrphanedReferences(referencedPaths: Collection<String>): Int = withContext(Dispatchers.IO) {
        val preservedPaths = synchronized(loadStateLock) { preservedRecords.sourcePaths() }
        deleteOrphanedReferencesInternal(referencedPaths + preservedPaths)
    }

    private fun encodeState(state: PersistedAppState, preserved: PreservedRecords): JSONObject = JSONObject().apply {
        put("schema", CURRENT_SCHEMA)
        put("savedAt", System.currentTimeMillis())
        put("settings", encodeSettings(state.settings))
        put("projects", JSONArray().apply {
            state.projects.forEach { put(encodeProject(it)) }
            preserved.projects.forEach(::put)
        })
        put("deletedProjects", JSONArray().apply {
            state.deletedProjects.forEach { put(encodeProject(it)) }
            preserved.deletedProjects.forEach(::put)
        })
        put("inventory", JSONArray().apply {
            state.inventory.forEach { item ->
                put(JSONObject().apply {
                    put("paletteId", item.paletteId)
                    put("colorCode", item.colorCode)
                    put("onHand", item.onHand)
                    put("bagSize", item.bagSize)
                })
            }
            preserved.inventory.forEach(::put)
        })
    }

    private fun decodeState(
        root: JSONObject,
        validateProject: ((BeadProject) -> String?)?,
        validateInventory: ((InventoryEntry) -> String?)?,
    ): DecodedState {
        val schema = decodeSchema(root)
        val projects = decodeProjects(root.requiredArray("projects"), schema, "projects", validateProject)
        val deletedProjects = decodeProjects(
            root.requiredArray("deletedProjects"),
            schema,
            "deletedProjects",
            validateProject,
        )
        val inventory = decodeInventory(root.requiredArray("inventory"), validateInventory)
        val settings = decodeSettings(root.requiredObject("settings"), schema)
        val issues = projects.issues + deletedProjects.issues + inventory.issues
        issues.forEach { issue ->
            Log.w(TAG, "保留无法读取的 ${issue.section}[${issue.index}]: ${issue.reason}")
        }
        return DecodedState(
            state = PersistedAppState(
                projects = projects.values,
                deletedProjects = deletedProjects.values,
                inventory = inventory.values,
                settings = settings,
            ),
            preserved = PreservedRecords(
                projects = projects.rejected,
                deletedProjects = deletedProjects.rejected,
                inventory = inventory.rejected,
            ),
            issues = issues,
        )
    }

    private fun decodeSchema(root: JSONObject): Int {
        val raw = root.opt("schema")
        val schema = (raw as? Number)?.let { number ->
            number.toInt().takeIf { number.toDouble() == it.toDouble() }
        }
        require(schema in SUPPORTED_SCHEMAS) {
            "Unsupported state schema: ${raw?.takeUnless { it === JSONObject.NULL } ?: "missing"}"
        }
        return requireNotNull(schema)
    }

    private fun encodeProject(project: BeadProject): JSONObject = JSONObject().apply {
        put("id", project.id)
        put("title", project.title)
        put("paletteId", project.paletteId)
        put("width", project.grid.width)
        put("height", project.grid.height)
        put("cells", encodeCells(project.grid.cells))
        put("completed", encodeBytes(project.grid.completed))
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
        project.sourcePath?.let { put("sourcePath", it) }
    }

    private fun decodeProjects(
        array: JSONArray,
        schema: Int,
        section: String,
        validateProject: ((BeadProject) -> String?)?,
    ): DecodedSection<BeadProject> = decodeSection(array, section) { item ->
        require(item is JSONObject) { "Project entry must be an object" }
        val width = item.getInt("width")
        val height = item.getInt("height")
        require(width in 1..MAX_GRID_DIMENSION && height in 1..MAX_GRID_DIMENSION) {
            "Grid dimensions are out of range"
        }
        val expectedCount = width * height
        val cells = decodeCells(item.getString("cells"), expectedCount)
        val completed = if (item.has("completed") && !item.isNull("completed")) {
            decodeBytes(item.getString("completed"), expectedCount)
        } else {
            ByteArray(expectedCount)
        }
        val id = item.getString("id")
        require(PROJECT_ID_PATTERN.matches(id)) { "Project ID is invalid" }
        BeadProject(
            id = id,
            title = item.optString("title", "未命名作品")
                .filterNot(Char::isISOControl).trim().take(512).ifBlank { "未命名作品" },
            paletteId = item.optString("paletteId", "mard-221"),
            grid = PatternGrid(width, height, cells, completed),
            sourceMode = enumValueOrDefault(item.optString("sourceMode"), ConversionMode.SPRITE),
            createdAt = item.optLong("createdAt", System.currentTimeMillis()),
            modifiedAt = item.optLong("modifiedAt", System.currentTimeMillis()),
            status = enumValueOrDefault(item.optString("status"), ProjectStatus.DRAFT),
            favorite = item.optBoolean("favorite", false),
            inventoryDeducted = item.optBoolean("inventoryDeducted", false),
            sourcePath = item.optString("sourcePath").takeIf(String::isNotBlank),
            boardSize = if (schema >= 2) {
                item.optInt("boardSize", BeadProject.DEFAULT_BOARD_SIZE)
                    .coerceIn(BeadProject.MIN_BOARD_SIZE, BeadProject.MAX_BOARD_SIZE)
            } else {
                BeadProject.DEFAULT_BOARD_SIZE
            },
            craftElapsedSeconds = if (schema >= 2) {
                item.optLong("craftElapsedSeconds", 0L).coerceAtLeast(0L)
            } else {
                0L
            },
            lastCraftBoardIndex = if (schema >= 2) {
                item.optInt("lastCraftBoardIndex", 0).coerceAtLeast(0)
            } else {
                0
            },
            tags = if (schema >= 2) {
                item.optJSONArray("tags").strings()
                    .map { it.filterNot(Char::isISOControl).trim().take(40) }
                    .filter(String::isNotEmpty)
                    .distinct()
                    .take(12)
                    .toList()
            } else {
                emptyList()
            },
            folder = if (schema >= 2) {
                item.optString("folder").filterNot(Char::isISOControl)
                    .trim().take(120).takeIf(String::isNotEmpty)
            } else {
                null
            },
        ).also { project ->
            validateProject?.invoke(project)?.let { reason ->
                throw IllegalArgumentException(reason)
            }
        }
    }

    private fun decodeInventory(
        array: JSONArray,
        validateInventory: ((InventoryEntry) -> String?)?,
    ): DecodedSection<InventoryEntry> = decodeSection(array, "inventory") { item ->
            require(item is JSONObject) { "Inventory entry must be an object" }
            InventoryEntry(
                paletteId = item.getString("paletteId").also {
                    require(it.isNotBlank()) { "Palette ID is blank" }
                },
                colorCode = item.getString("colorCode").also {
                    require(it.isNotBlank()) { "Color code is blank" }
                },
                onHand = item.optInt("onHand", 0).coerceIn(0, 999_999),
                bagSize = item.optInt("bagSize", 1_000).coerceIn(1, 999_999),
            ).also { entry ->
                validateInventory?.invoke(entry)?.let { reason ->
                    throw IllegalArgumentException(reason)
                }
            }
        }

    private fun encodeSettings(settings: AppSettings): JSONObject = JSONObject().apply {
        put("themeMode", settings.themeMode.name)
        put("defaultPaletteId", settings.defaultPaletteId)
        put("showColorCodes", settings.showColorCodes)
        put("highContrastGrid", settings.highContrastGrid)
        put("keepScreenOnInCraftMode", settings.keepScreenOnInCraftMode)
        put("confirmInventoryDeduction", settings.confirmInventoryDeduction)
        put("hasSeenTutorial", settings.hasSeenTutorial)
        put("defaultBoardSize", settings.defaultBoardSize)
        put("lowStockThreshold", settings.lowStockThreshold)
    }

    private fun decodeSettings(item: JSONObject, schema: Int): AppSettings = AppSettings(
        themeMode = enumValueOrDefault(item.optString("themeMode"), AppThemeMode.SYSTEM),
        defaultPaletteId = item.optString("defaultPaletteId", "mard-221"),
        showColorCodes = item.optBoolean("showColorCodes", true),
        highContrastGrid = item.optBoolean("highContrastGrid", false),
        keepScreenOnInCraftMode = item.optBoolean("keepScreenOnInCraftMode", true),
        confirmInventoryDeduction = item.optBoolean("confirmInventoryDeduction", true),
        hasSeenTutorial = item.optBoolean("hasSeenTutorial", false),
        defaultBoardSize = if (schema >= 2) {
            item.optInt("defaultBoardSize", BeadProject.DEFAULT_BOARD_SIZE)
                .coerceIn(BeadProject.MIN_BOARD_SIZE, BeadProject.MAX_BOARD_SIZE)
        } else {
            BeadProject.DEFAULT_BOARD_SIZE
        },
        lowStockThreshold = if (schema >= 2) {
            item.optInt("lowStockThreshold", 300).coerceIn(0, 999_999)
        } else {
            300
        },
    )

    private fun encodeCells(cells: IntArray): String {
        val buffer = ByteBuffer.allocate(cells.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        cells.forEach { value ->
            require(value in Short.MIN_VALUE..Short.MAX_VALUE) { "Palette index is out of range" }
            buffer.putShort(value.toShort())
        }
        return encodeBytes(buffer.array())
    }

    private fun decodeCells(encoded: String, expectedCount: Int): IntArray {
        val bytes = decodeRawBytes(encoded, expectedCount * 2)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return IntArray(expectedCount) { buffer.short.toInt() }
    }

    private fun encodeBytes(bytes: ByteArray): String {
        val target = ByteArrayOutputStream()
        GZIPOutputStream(target).use { it.write(bytes) }
        return Base64.encodeToString(target.toByteArray(), Base64.NO_WRAP)
    }

    private fun decodeBytes(encoded: String, expectedCount: Int): ByteArray {
        if (encoded.isBlank()) return ByteArray(expectedCount)
        return decodeRawBytes(encoded, expectedCount)
    }

    private fun decodeRawBytes(encoded: String, expectedSize: Int): ByteArray {
        require(encoded.length <= MAX_ENCODED_GRID_LENGTH) { "Grid payload is too large" }
        val compressed = Base64.decode(encoded, Base64.NO_WRAP)
        return GZIPInputStream(ByteArrayInputStream(compressed)).use { input ->
            val output = ByteArrayOutputStream(expectedSize)
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= expectedSize) { "Grid payload has an unexpected size" }
                output.write(buffer, 0, read)
            }
            require(total == expectedSize) { "Grid payload has an unexpected size" }
            output.toByteArray()
        }
    }

    private inline fun <T> decodeSection(
        array: JSONArray,
        section: String,
        decode: (Any) -> T,
    ): DecodedSection<T> {
        val values = mutableListOf<T>()
        val rejected = mutableListOf<Any>()
        val issues = mutableListOf<ProjectRepositoryLoadIssue>()
        for (index in 0 until array.length()) {
            val raw = array.get(index)
            try {
                values += decode(raw)
            } catch (error: Exception) {
                rejected += copyJsonValue(raw)
                issues += ProjectRepositoryLoadIssue(
                    section = section,
                    index = index,
                    reason = error.message?.take(MAX_ISSUE_REASON_LENGTH)
                        ?: error.javaClass.simpleName,
                )
            }
        }
        return DecodedSection(values, rejected, issues)
    }

    private fun copyJsonValue(value: Any): Any {
        if (value === JSONObject.NULL) return JSONObject.NULL
        return when (value) {
            is JSONObject -> JSONObject(value.toString())
            is JSONArray -> JSONArray(value.toString())
            else -> value
        }
    }

    private fun JSONObject.requiredArray(name: String): JSONArray =
        opt(name) as? JSONArray ?: throw IllegalArgumentException("$name must be an array")

    private fun JSONObject.requiredObject(name: String): JSONObject =
        opt(name) as? JSONObject ?: throw IllegalArgumentException("$name must be an object")

    private fun updateSuccessfulLoad(decoded: DecodedState) {
        synchronized(loadStateLock) {
            saveBlockedByLoadFailure = false
            preservedRecords = decoded.preserved
            loadIssues = decoded.issues
        }
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(raw: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == raw } ?: fallback

    private fun writeAtomically(destination: File, write: (FileOutputStream) -> Unit): String {
        val target = AtomicFile(destination)
        val output = target.startWrite()
        try {
            write(output)
            target.finishWrite(output)
            return destination.absolutePath
        } catch (error: Throwable) {
            target.failWrite(output)
            throw error
        }
    }

    private inline fun <T> fileOperationOrNull(operation: () -> T): T? = try {
        operation()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun deleteProjectReferencesInternal(projectIds: Collection<String>): Int {
        var deleted = 0
        for (projectId in projectIds.distinct()) {
            for (suffix in listOf(LEGACY_SOURCE_SUFFIX, REFERENCE_SUFFIX)) {
                val reference = managedProjectFile(projectId, suffix) ?: continue
                val existed = reference.exists() || File("${reference.path}.bak").exists()
                AtomicFile(reference).delete()
                if (existed) deleted++
            }
            managedProjectFile(projectId, LEGACY_REFERENCE_TEMP_SUFFIX)?.delete()
        }
        return deleted
    }

    private fun deleteOrphanedReferencesInternal(referencedPaths: Collection<String>): Int {
        val retained = canonicalManagedReferencePaths(referencedPaths) ?: return 0
        val entries = sourceDirectory.listFiles() ?: return 0
        val candidates = linkedMapOf<String, File>()
        for (entry in entries) {
            val candidate = managedReferenceBaseForEntry(entry) ?: continue
            val canonicalPath = runCatching { candidate.canonicalPath }.getOrNull() ?: continue
            candidates.putIfAbsent(canonicalPath, candidate)
        }

        var deleted = 0
        for ((canonicalPath, candidate) in candidates) {
            if (canonicalPath in retained) continue
            val backup = File("${candidate.path}$ATOMIC_BACKUP_SUFFIX")
            val existed = candidate.isFile || backup.isFile
            val removed = runCatching {
                AtomicFile(candidate).delete()
                !candidate.exists() && !backup.exists()
            }.getOrDefault(false)
            if (existed && removed) deleted++
        }
        return deleted
    }

    private fun canonicalManagedReferencePaths(paths: Collection<String>): Set<String>? {
        val retained = hashSetOf<String>()
        for (path in paths) {
            val canonical = try {
                File(path).canonicalFile
            } catch (_: Exception) {
                return null
            }
            if (canonical.parentFile == sourceDirectory && isManagedReferenceName(canonical.name)) {
                retained += canonical.path
            }
        }
        return retained
    }

    private fun managedReferenceBaseForEntry(entry: File): File? {
        if (!entry.isFile) return null
        val canonicalEntry = runCatching { entry.canonicalFile }.getOrNull() ?: return null
        val canonicalParent = runCatching { entry.parentFile?.canonicalFile }.getOrNull() ?: return null
        if (canonicalParent != sourceDirectory) return null
        if (canonicalEntry != entry.absoluteFile) return null
        val baseName = when {
            isManagedReferenceName(entry.name) -> entry.name
            entry.name.endsWith(ATOMIC_BACKUP_SUFFIX) ->
                entry.name.removeSuffix(ATOMIC_BACKUP_SUFFIX).takeIf(::isManagedReferenceName)
            else -> null
        } ?: return null
        val candidate = File(sourceDirectory, baseName).absoluteFile
        if (candidate.exists()) {
            val canonicalCandidate = runCatching { candidate.canonicalFile }.getOrNull() ?: return null
            if (!candidate.isFile || canonicalCandidate != candidate) return null
        }
        return candidate
    }

    private fun managedProjectFile(projectId: String, suffix: String): File? {
        if (!PROJECT_ID_PATTERN.matches(projectId)) return null
        val candidate = File(sourceDirectory, "$projectId$suffix").absoluteFile
        if (candidate.parentFile?.canonicalFile != sourceDirectory) return null
        if (candidate.exists() && candidate.canonicalFile != candidate) return null
        return candidate
    }

    private fun managedReferenceFile(path: String): File? {
        val requested = File(path).absoluteFile
        if (requested.parentFile?.canonicalFile != sourceDirectory || !isManagedReferenceName(requested.name)) return null
        val canonical = runCatching { requested.canonicalFile }.getOrNull() ?: return null
        return canonical.takeIf { it.parentFile == sourceDirectory && it.isFile }
    }

    private fun isManagedReferenceName(name: String): Boolean {
        val projectId = when {
            name.endsWith(REFERENCE_SUFFIX) -> name.removeSuffix(REFERENCE_SUFFIX)
            name.endsWith(LEGACY_SOURCE_SUFFIX) -> name.removeSuffix(LEGACY_SOURCE_SUFFIX)
            else -> return false
        }
        return PROJECT_ID_PATTERN.matches(projectId)
    }

    private fun JSONArray?.strings(): Sequence<String> = sequence {
        val source = this@strings ?: return@sequence
        for (index in 0 until source.length()) yield(source.optString(index))
    }

    private data class DecodedState(
        val state: PersistedAppState,
        val preserved: PreservedRecords = PreservedRecords(),
        val issues: List<ProjectRepositoryLoadIssue> = emptyList(),
    )

    private data class DecodedSection<T>(
        val values: List<T>,
        val rejected: List<Any>,
        val issues: List<ProjectRepositoryLoadIssue>,
    )

    private data class PreservedRecords(
        val projects: List<Any> = emptyList(),
        val deletedProjects: List<Any> = emptyList(),
        val inventory: List<Any> = emptyList(),
    ) {
        fun sourcePaths(): List<String> = (projects + deletedProjects).mapNotNull { record ->
            (record as? JSONObject)
                ?.opt("sourcePath")
                ?.takeIf { it is String }
                ?.toString()
                ?.takeIf(String::isNotBlank)
        }
    }

    private companion object {
        const val TAG = "ProjectRepository"
        const val CURRENT_SCHEMA = 2
        val SUPPORTED_SCHEMAS = setOf(1, CURRENT_SCHEMA)
        const val MAX_GRID_DIMENSION = 512
        const val MAX_ENCODED_GRID_LENGTH = 800_000
        const val MAX_ISSUE_REASON_LENGTH = 240
        val PROJECT_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
        const val LEGACY_SOURCE_SUFFIX = "-source"
        const val REFERENCE_SUFFIX = "-reference.png"
        const val LEGACY_REFERENCE_TEMP_SUFFIX = "-reference.tmp"
        const val ATOMIC_BACKUP_SUFFIX = ".bak"
    }
}
