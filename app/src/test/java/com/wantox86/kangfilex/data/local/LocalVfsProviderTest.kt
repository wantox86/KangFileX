package com.wantox86.kangfilex.data.local

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files

class LocalVfsProviderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun connectDisconnectAndIsConnected() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val provider = LocalVfsProvider(ioDispatcher = dispatcher)

        assertTrue(provider.isConnected())
        provider.disconnect()
        assertFalse(provider.isConnected())
        assertTrue(provider.connect().isSuccess)
        assertTrue(provider.isConnected())
    }

    @Test
    fun listFilesSortsFoldersFirstCaseInsensitiveAndCountsDirectChildren() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val provider = LocalVfsProvider(ioDispatcher = dispatcher)

        val root = tempFolder.newFolder("root")
        val dirBeta = File(root, "beta").apply { mkdir() }
        File(dirBeta, "sub1").mkdir()
        File(dirBeta, "doc1.txt").writeText("hello")
        File(dirBeta, "doc2.txt").writeText("world")

        File(root, "Alpha").mkdir()
        File(root, "zeta.txt").writeText("12345")
        File(root, "Apple.apk").writeText("123")

        val result = provider.listFiles(root.absolutePath)
        assertTrue(result.isSuccess)
        val items = result.getOrThrow()

        assertEquals(listOf("Alpha", "beta", "Apple.apk", "zeta.txt"), items.map { it.name })
        assertTrue(items[0].isDirectory)
        assertTrue(items[1].isDirectory)
        assertFalse(items[2].isDirectory)
        assertFalse(items[3].isDirectory)

        val betaItem = items[1]
        assertEquals(1, betaItem.childDirCount)
        assertEquals(2, betaItem.childFileCount)
        assertEquals(5L, items[3].size)
    }

    @Test
    fun createDirectoryExistsRenameAndDelete() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val provider = LocalVfsProvider(ioDispatcher = dispatcher)

        val nestedDir = File(tempFolder.root, "parent/child").absolutePath
        assertFalse(provider.exists(nestedDir))

        assertTrue(provider.createDirectory(nestedDir).isSuccess)
        assertTrue(provider.exists(nestedDir))

        val renamedDir = File(tempFolder.root, "parent/child_renamed").absolutePath
        assertTrue(provider.rename(nestedDir, renamedDir).isSuccess)
        assertFalse(provider.exists(nestedDir))
        assertTrue(provider.exists(renamedDir))

        // Add a file inside so non-recursive delete fails
        val innerFile = File(renamedDir, "note.txt").absolutePath
        provider.openOutputStream(innerFile, overwrite = false).use { it.write("hi".toByteArray()) }

        assertTrue(provider.delete(renamedDir, recursive = false).isFailure)
        assertTrue(provider.exists(renamedDir))

        assertTrue(provider.delete(renamedDir, recursive = true).isSuccess)
        assertFalse(provider.exists(renamedDir))
    }

    @Test
    fun streamReadWriteAndOverwriteGuard() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val provider = LocalVfsProvider(ioDispatcher = dispatcher)

        val filePath = File(tempFolder.root, "sub/data.bin").absolutePath
        provider.openOutputStream(filePath, overwrite = false).use { out ->
            out.write("KangFileX-v1".toByteArray())
        }

        val read1 = provider.openInputStream(filePath).use { it.readBytes().decodeToString() }
        assertEquals("KangFileX-v1", read1)

        var threwOnNoOverwrite = false
        try {
            provider.openOutputStream(filePath, overwrite = false).close()
        } catch (_: IOException) {
            threwOnNoOverwrite = true
        }
        assertTrue(threwOnNoOverwrite)

        provider.openOutputStream(filePath, overwrite = true).use { out ->
            out.write("KangFileX-v2".toByteArray())
        }
        val read2 = provider.openInputStream(filePath).use { it.readBytes().decodeToString() }
        assertEquals("KangFileX-v2", read2)
    }

    @Test
    fun renameCaseOnlySucceedsAndUpdatesFileName() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val provider = LocalVfsProvider(ioDispatcher = dispatcher)

        val dir = tempFolder.newFolder("case_only_rename")
        val source = File(dir, "a").apply { writeText("hello-case") }
        val dest = File(dir, "A")

        val result = provider.rename(source.absolutePath, dest.absolutePath)
        assertTrue(result.isSuccess)
        assertTrue(dest.exists())
        assertEquals("hello-case", dest.readText())

        val names = dir.listFiles()?.map { it.name }.orEmpty()
        assertTrue(names.contains("A"))
        assertFalse(names.contains("a"))
    }

    @Test
    fun renameCaseOnlyRejectsDistinctExistingFileOnCaseSensitiveFilesystem() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val provider = LocalVfsProvider(ioDispatcher = dispatcher)

        val dir = tempFolder.newFolder("case_sensitive_collision")
        val source = File(dir, "a").apply { writeText("lower-content") }
        val dest = File(dir, "A")

        Assume.assumeFalse("Filesystem is case-insensitive", dest.exists())
        dest.writeText("upper-content")
        Assume.assumeFalse(
            "Filesystem treats 'a' and 'A' as the same file",
            Files.isSameFile(source.toPath(), dest.toPath())
        )

        val result = provider.rename(source.absolutePath, dest.absolutePath)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IOException)
        assertTrue(source.exists())
        assertEquals("lower-content", source.readText())
        assertTrue(dest.exists())
        assertEquals("upper-content", dest.readText())
    }

    @Test
    fun renameCaseOnlyRollsBackWhenSecondStepFails() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val provider = LocalVfsProvider(
            ioDispatcher = dispatcher,
            renameOp = { from, to ->
                if (to.name == "A") false else from.renameTo(to)
            }
        )

        val dir = tempFolder.newFolder("case_only_rollback")
        val source = File(dir, "a").apply { writeText("x") }
        val dest = File(dir, "A")
        Assume.assumeTrue("Filesystem is case-sensitive", dest.exists())

        val result = provider.rename(source.absolutePath, dest.absolutePath)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IOException)

        val names = dir.list().orEmpty().toList()
        assertTrue(names.contains("a"))
        assertFalse(names.any { it.startsWith(".kfx-rename-") })
        assertEquals("x", source.readText())
    }

    @Test
    fun renameCaseOnlyUsesTwoStepRenameOnCaseInsensitiveFilesystem() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val recordedSteps = mutableListOf<Pair<String, String>>()
        val provider = LocalVfsProvider(
            ioDispatcher = dispatcher,
            renameOp = { from, to ->
                recordedSteps += from.name to to.name
                from.renameTo(to)
            }
        )

        val dir = tempFolder.newFolder("case_only_two_step_success")
        val source = File(dir, "a").apply { writeText("two-step") }
        val dest = File(dir, "A")
        Assume.assumeTrue("Filesystem is case-sensitive", dest.exists())

        val result = provider.rename(source.absolutePath, dest.absolutePath)
        assertTrue(result.isSuccess)
        assertEquals(2, recordedSteps.size)
        assertEquals("a", recordedSteps[0].first)
        assertTrue(recordedSteps[0].second.startsWith(".kfx-rename-"))
        assertEquals(recordedSteps[0].second, recordedSteps[1].first)
        assertEquals("A", recordedSteps[1].second)

        val names = dir.list().orEmpty().toList()
        assertTrue(names.contains("A"))
        assertFalse(names.contains("a"))
        assertFalse(names.any { it.startsWith(".kfx-rename-") })
        assertEquals("two-step", dest.readText())
    }

    @Test
    fun renameCaseOnlyFailsWhenFilesystemNameDoesNotChange() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val provider = LocalVfsProvider(
            ioDispatcher = dispatcher,
            renameOp = { from, to ->
                if (to.name == "A") true else from.renameTo(to)
            }
        )

        val dir = tempFolder.newFolder("case_only_verify_fail")
        val source = File(dir, "a").apply { writeText("verify-content") }
        val dest = File(dir, "A")
        Assume.assumeTrue("Filesystem is case-sensitive", dest.exists())

        val result = provider.rename(source.absolutePath, dest.absolutePath)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IOException)

        val files = dir.listFiles().orEmpty()
        assertTrue(files.any { it.isFile && it.readText() == "verify-content" })
    }

    @Test
    fun renameCaseOnlyRejectsSymlinkDestinationOnCaseSensitiveFilesystem() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val provider = LocalVfsProvider(ioDispatcher = dispatcher)

        val dir = tempFolder.newFolder("case_sensitive_symlink_collision")
        val source = File(dir, "a").apply { writeText("t") }
        val dest = File(dir, "A")

        Assume.assumeFalse("Filesystem is case-insensitive", dest.exists())
        try {
            Files.createSymbolicLink(dest.toPath(), source.toPath())
        } catch (e: Throwable) {
            Assume.assumeNoException("Symbolic links are not supported", e)
        }

        val result = provider.rename(source.absolutePath, dest.absolutePath)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IOException)
        assertTrue(source.exists())
        assertEquals("t", source.readText())
        assertTrue(Files.isSymbolicLink(dest.toPath()))
    }
}
