package com.qiao.dougrid.data

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.qiao.dougrid.core.EMPTY_CELL
import com.qiao.dougrid.core.PatternGrid
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ProjectRepositoryTest {
    @Test
    fun schemaOneLoadsWithMigrationDefaultsAndSavesAsSchemaTwo() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val state = stateFile(context)
        clearStateFile(state)
        val original = sampleState(
            project = sampleProject().copy(
                boardSize = 40,
                craftElapsedSeconds = 99,
                lastCraftBoardIndex = 3,
                tags = listOf("礼物"),
                folder = "生日",
            ),
            settings = AppSettings(defaultBoardSize = 40, lowStockThreshold = 12),
        )

        try {
            ProjectRepository(context).save(original)
            val legacyRoot = JSONObject(state.readText()).apply {
                put("schema", 1)
                getJSONObject("settings").apply {
                    remove("defaultBoardSize")
                    remove("lowStockThreshold")
                }
                getJSONArray("projects").getJSONObject(0).apply {
                    remove("boardSize")
                    remove("craftElapsedSeconds")
                    remove("lastCraftBoardIndex")
                    remove("tags")
                    remove("folder")
                }
            }
            state.writeText(legacyRoot.toString())

            val repository = ProjectRepository(context)
            val migrated = repository.load()

            assertEquals(BeadProject.DEFAULT_BOARD_SIZE, migrated.projects.single().boardSize)
            assertEquals(0L, migrated.projects.single().craftElapsedSeconds)
            assertTrue(migrated.projects.single().tags.isEmpty())
            assertNull(migrated.projects.single().folder)
            assertEquals(BeadProject.DEFAULT_BOARD_SIZE, migrated.settings.defaultBoardSize)
            assertEquals(300, migrated.settings.lowStockThreshold)
            assertTrue(repository.loadIssues.isEmpty())

            repository.save(migrated)
            assertEquals(2, JSONObject(state.readText()).getInt("schema"))
        } finally {
            clearStateFile(state)
        }
    }

    @Test
    fun unreadableStateThrowsAndBlocksAnAccidentalOverwrite() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val state = stateFile(context)
        clearStateFile(state)
        val invalidContents = "{not valid json"
        state.writeText(invalidContents)
        val repository = ProjectRepository(context)

        try {
            val loadFailure = runCatching { repository.load() }.exceptionOrNull()
            assertTrue(loadFailure is ProjectRepositoryLoadException)

            val saveFailure = runCatching { repository.save(sampleState()) }.exceptionOrNull()
            assertTrue(saveFailure is ProjectRepositorySaveBlockedException)
            assertEquals(invalidContents, state.readText())
        } finally {
            clearStateFile(state)
        }
    }

    @Test
    fun unsupportedSchemaThrowsAndBlocksAnAccidentalOverwrite() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val state = stateFile(context)
        clearStateFile(state)
        val unsupported = JSONObject().apply { put("schema", 99) }.toString()
        state.writeText(unsupported)
        val repository = ProjectRepository(context)

        try {
            val failure = runCatching { repository.load() }.exceptionOrNull()
            assertTrue(failure is ProjectRepositoryLoadException)
            assertTrue(failure?.cause is IllegalArgumentException)
            assertTrue(runCatching { repository.save(sampleState()) }.exceptionOrNull() is ProjectRepositorySaveBlockedException)
            assertEquals(unsupported, state.readText())
        } finally {
            clearStateFile(state)
        }
    }

    @Test
    fun damagedProjectIsReportedAndPreservedWhileValidProjectsRemainUsable() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val state = stateFile(context)
        clearStateFile(state)
        val valid = sampleProject()

        try {
            ProjectRepository(context).save(sampleState(valid))
            val root = JSONObject(state.readText())
            root.getJSONArray("projects").put(
                JSONObject().apply {
                    put("id", "damaged-project")
                    put("title", "必须保留的损坏记录")
                    put("width", 2)
                    put("height", 2)
                    put("cells", "not-a-grid")
                    put("recoveryMarker", "keep-me")
                },
            )
            state.writeText(root.toString())

            val repository = ProjectRepository(context)
            val loaded = repository.load()
            assertEquals(listOf(valid.id), loaded.projects.map(BeadProject::id))
            assertEquals(1, repository.loadIssues.size)
            assertEquals("projects", repository.loadIssues.single().section)
            assertEquals(1, repository.loadIssues.single().index)

            repository.save(loaded)
            val savedProjects = JSONObject(state.readText()).getJSONArray("projects")
            assertEquals(2, savedProjects.length())
            assertEquals("keep-me", savedProjects.getJSONObject(1).getString("recoveryMarker"))
        } finally {
            clearStateFile(state)
        }
    }

    @Test
    fun referencePngWriteFailureIsPropagated() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val repository = ProjectRepository(context)
        val projectId = UUID.randomUUID().toString()
        val collision = File(context.filesDir, "project-sources/$projectId-reference.png")
        collision.mkdirs()

        try {
            val failure = runCatching {
                repository.saveReferencePng(byteArrayOf(1, 2, 3), projectId)
            }.exceptionOrNull()
            assertNotNull(failure)
            assertTrue(collision.isDirectory)
        } finally {
            collision.deleteRecursively()
            File("${collision.path}.bak").delete()
        }
    }

    @Test
    fun referenceCopyAndCleanupStayInsideManagedDirectory() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val repository = ProjectRepository(context)
        val sourceDirectory = File(context.filesDir, "project-sources").apply { mkdirs() }
        val sourceId = UUID.randomUUID().toString()
        val copiedId = UUID.randomUUID().toString()
        val legacyId = UUID.randomUUID().toString()
        val legacyCopyId = UUID.randomUUID().toString()
        val bitmap = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.MAGENTA)
        }
        val unrelated = File(sourceDirectory, "unrelated.keep")
        val outside = File(context.filesDir, "${UUID.randomUUID()}-reference.png")
        val legacy = File(sourceDirectory, "$legacyId-source")

        try {
            unrelated.writeText("keep")
            outside.writeText("outside")
            legacy.writeBytes(byteArrayOf(4, 8, 15, 16, 23, 42))

            val savedPath = repository.saveReference(bitmap, sourceId)
            assertNotNull(savedPath)
            val saved = File(requireNotNull(savedPath))
            val copiedPath = repository.copyReference(saved.absolutePath, copiedId)
            assertNotNull(copiedPath)
            val copied = File(requireNotNull(copiedPath))
            assertNotEquals(saved.absolutePath, copied.absolutePath)
            assertArrayEquals(saved.readBytes(), copied.readBytes())

            val legacyCopiedPath = repository.copyReference(legacy.absolutePath, legacyCopyId)
            assertNotNull(legacyCopiedPath)
            assertArrayEquals(legacy.readBytes(), File(requireNotNull(legacyCopiedPath)).readBytes())
            assertNull(repository.copyReference(outside.absolutePath, UUID.randomUUID().toString()))
            assertNull(repository.copyReference(saved.absolutePath, "../outside"))

            assertEquals(
                4,
                repository.deleteProjectReferences(listOf(sourceId, copiedId, legacyId, legacyCopyId, "../outside")),
            )
            assertFalse(saved.exists())
            assertFalse(copied.exists())
            assertFalse(legacy.exists())
            assertFalse(File(requireNotNull(legacyCopiedPath)).exists())
            assertTrue(unrelated.exists())
            assertTrue(outside.exists())
        } finally {
            bitmap.recycle()
            listOf(sourceId, copiedId, legacyId, legacyCopyId).forEach { id ->
                File(sourceDirectory, "$id-source").delete()
                File(sourceDirectory, "$id-reference.png").delete()
                File(sourceDirectory, "$id-reference.png.bak").delete()
                File(sourceDirectory, "$id-reference.tmp").delete()
            }
            unrelated.delete()
            outside.delete()
        }
    }

    @Test
    fun orphanCleanupKeepsSharedReferencesUntilTheyAreNoLongerReferenced() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val repository = ProjectRepository(context)
        val sourceDirectory = File(context.filesDir, "project-sources").apply { mkdirs() }
        val existingReferences = managedReferenceBasePaths(sourceDirectory)
        val sharedId = UUID.randomUUID().toString()
        val orphanId = UUID.randomUUID().toString()
        val backupOnlyId = UUID.randomUUID().toString()
        val sharedByProjectB = File(sourceDirectory, "$sharedId-source")
        val orphan = File(sourceDirectory, "$orphanId-reference.png")
        val backupOnly = File(sourceDirectory, "$backupOnlyId-reference.png.bak")
        val unrelated = File(sourceDirectory, "${UUID.randomUUID()}-reference.png.tmp")

        try {
            sharedByProjectB.writeText("shared")
            orphan.writeText("orphan")
            backupOnly.writeText("backup")
            unrelated.writeText("keep")

            assertEquals(
                2,
                repository.deleteOrphanedReferences(existingReferences + sharedByProjectB.absolutePath),
            )
            assertTrue(sharedByProjectB.exists())
            assertFalse(orphan.exists())
            assertFalse(backupOnly.exists())
            assertTrue(unrelated.exists())

            assertEquals(1, repository.deleteOrphanedReferences(existingReferences))
            assertFalse(sharedByProjectB.exists())
            assertTrue(unrelated.exists())
        } finally {
            sharedByProjectB.delete()
            orphan.delete()
            File("${orphan.path}.bak").delete()
            backupOnly.delete()
            File(sourceDirectory, "$backupOnlyId-reference.png").delete()
            unrelated.delete()
        }
    }

    private fun managedReferenceBasePaths(directory: File): List<String> =
        directory.listFiles().orEmpty().mapNotNull { entry ->
            val baseName = entry.name.removeSuffix(".bak")
            val projectId = when {
                baseName.endsWith("-reference.png") -> baseName.removeSuffix("-reference.png")
                baseName.endsWith("-source") -> baseName.removeSuffix("-source")
                else -> return@mapNotNull null
            }
            if (!Regex("[A-Za-z0-9_-]{1,128}").matches(projectId)) return@mapNotNull null
            File(directory, baseName).absolutePath
        }.distinct()

    private fun sampleProject(id: String = UUID.randomUUID().toString()): BeadProject = BeadProject(
        id = id,
        title = "仓储测试",
        paletteId = "mard-221",
        grid = PatternGrid(
            width = 2,
            height = 1,
            cells = intArrayOf(0, EMPTY_CELL),
            completed = byteArrayOf(1, 0),
        ),
    )

    private fun sampleState(
        project: BeadProject = sampleProject(),
        settings: AppSettings = AppSettings(),
    ): PersistedAppState = PersistedAppState(
        projects = listOf(project),
        inventory = listOf(InventoryEntry("mard-221", "A1", 500)),
        settings = settings,
    )

    private fun stateFile(context: Application): File = File(context.filesDir, "dougrid-state-v1.json")

    private fun clearStateFile(file: File) {
        file.delete()
        File("${file.path}.bak").delete()
        File("${file.path}.new").delete()
    }
}
