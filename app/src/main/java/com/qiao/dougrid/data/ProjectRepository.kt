package com.qiao.dougrid.data

import android.content.Context
import android.graphics.Bitmap
import android.util.AtomicFile
import android.util.Base64
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class ProjectRepository(context: Context) {
    private val stateFile = AtomicFile(File(context.filesDir, "dougrid-state-v1.json"))
    private val sourceDirectory = File(context.filesDir, "project-sources").apply { mkdirs() }.canonicalFile

    suspend fun load(): PersistedAppState = withContext(Dispatchers.IO) {
        runCatching {
            stateFile.openRead().bufferedReader(Charsets.UTF_8).use { reader ->
                decodeState(JSONObject(reader.readText()))
            }
        }.getOrElse {
            PersistedAppState(emptyList(), emptyList(), AppSettings())
        }
    }

    suspend fun save(state: PersistedAppState) = withContext(Dispatchers.IO) {
        val output = stateFile.startWrite()
        try {
            val writer = output.bufferedWriter(Charsets.UTF_8)
            writer.write(encodeState(state).toString())
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

    suspend fun saveReference(bitmap: Bitmap, projectId: String): String? =
        withContext(Dispatchers.IO) {
            val destination = managedProjectFile(projectId, REFERENCE_SUFFIX) ?: return@withContext null
            fileOperationOrNull {
                writeAtomically(destination) { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                        "无法编码裁剪参考图"
                    }
                }
            }
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
        deleteOrphanedReferencesInternal(referencedPaths)
    }

    private fun encodeState(state: PersistedAppState): JSONObject = JSONObject().apply {
        put("schema", 1)
        put("savedAt", System.currentTimeMillis())
        put("settings", encodeSettings(state.settings))
        put("projects", JSONArray().apply { state.projects.forEach { put(encodeProject(it)) } })
        put("deletedProjects", JSONArray().apply { state.deletedProjects.forEach { put(encodeProject(it)) } })
        put("inventory", JSONArray().apply {
            state.inventory.forEach { item ->
                put(JSONObject().apply {
                    put("paletteId", item.paletteId)
                    put("colorCode", item.colorCode)
                    put("onHand", item.onHand)
                    put("bagSize", item.bagSize)
                })
            }
        })
    }

    private fun decodeState(root: JSONObject): PersistedAppState = PersistedAppState(
        projects = decodeProjects(root.optJSONArray("projects")),
        deletedProjects = decodeProjects(root.optJSONArray("deletedProjects")),
        inventory = root.optJSONArray("inventory").objects().mapNotNull { item ->
            runCatching {
                InventoryEntry(
                    paletteId = item.getString("paletteId"),
                    colorCode = item.getString("colorCode"),
                    onHand = item.optInt("onHand", 0).coerceAtLeast(0),
                    bagSize = item.optInt("bagSize", 1_000).coerceAtLeast(1),
                )
            }.getOrNull()
        }.toList(),
        settings = decodeSettings(root.optJSONObject("settings")),
    )

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
        project.sourcePath?.let { put("sourcePath", it) }
    }

    private fun decodeProjects(array: JSONArray?): List<BeadProject> = array.objects().mapNotNull { item ->
        runCatching {
            val width = item.getInt("width")
            val height = item.getInt("height")
            val cells = decodeCells(item.getString("cells"), width * height)
            val completed = decodeBytes(item.optString("completed"), width * height)
            BeadProject(
                id = item.getString("id"),
                title = item.optString("title", "未命名作品"),
                paletteId = item.optString("paletteId", "mard-221"),
                grid = PatternGrid(width, height, cells, completed),
                sourceMode = enumValueOrDefault(item.optString("sourceMode"), ConversionMode.SPRITE),
                createdAt = item.optLong("createdAt", System.currentTimeMillis()),
                modifiedAt = item.optLong("modifiedAt", System.currentTimeMillis()),
                status = enumValueOrDefault(item.optString("status"), ProjectStatus.DRAFT),
                favorite = item.optBoolean("favorite", false),
                inventoryDeducted = item.optBoolean("inventoryDeducted", false),
                sourcePath = item.optString("sourcePath").takeIf(String::isNotBlank),
            )
        }.getOrNull()
    }.toList()

    private fun encodeSettings(settings: AppSettings): JSONObject = JSONObject().apply {
        put("themeMode", settings.themeMode.name)
        put("defaultPaletteId", settings.defaultPaletteId)
        put("showColorCodes", settings.showColorCodes)
        put("highContrastGrid", settings.highContrastGrid)
        put("keepScreenOnInCraftMode", settings.keepScreenOnInCraftMode)
        put("confirmInventoryDeduction", settings.confirmInventoryDeduction)
        put("hasSeenTutorial", settings.hasSeenTutorial)
    }

    private fun decodeSettings(item: JSONObject?): AppSettings = AppSettings(
        themeMode = enumValueOrDefault(item?.optString("themeMode"), AppThemeMode.SYSTEM),
        defaultPaletteId = item?.optString("defaultPaletteId", "mard-221") ?: "mard-221",
        showColorCodes = item?.optBoolean("showColorCodes", true) ?: true,
        highContrastGrid = item?.optBoolean("highContrastGrid", false) ?: false,
        keepScreenOnInCraftMode = item?.optBoolean("keepScreenOnInCraftMode", true) ?: true,
        confirmInventoryDeduction = item?.optBoolean("confirmInventoryDeduction", true) ?: true,
        hasSeenTutorial = item?.optBoolean("hasSeenTutorial", false) ?: false,
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
        val bytes = decodeRawBytes(encoded)
        require(bytes.size == expectedCount * 2) { "Grid payload has an unexpected size" }
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
        val bytes = decodeRawBytes(encoded)
        return bytes.copyOf(expectedCount)
    }

    private fun decodeRawBytes(encoded: String): ByteArray {
        val compressed = Base64.decode(encoded, Base64.NO_WRAP)
        return GZIPInputStream(ByteArrayInputStream(compressed)).use { it.readBytes() }
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

    private fun JSONArray?.objects(): Sequence<JSONObject> = sequence {
        val source = this@objects ?: return@sequence
        for (index in 0 until source.length()) source.optJSONObject(index)?.let { yield(it) }
    }

    private companion object {
        val PROJECT_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
        const val LEGACY_SOURCE_SUFFIX = "-source"
        const val REFERENCE_SUFFIX = "-reference.png"
        const val LEGACY_REFERENCE_TEMP_SUFFIX = "-reference.tmp"
        const val ATOMIC_BACKUP_SUFFIX = ".bak"
    }
}
