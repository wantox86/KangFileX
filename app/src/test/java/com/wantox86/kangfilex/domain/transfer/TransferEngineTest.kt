package com.wantox86.kangfilex.domain.transfer

import com.wantox86.kangfilex.data.local.LocalVfsProvider
import com.wantox86.kangfilex.domain.model.VfsFile
import com.wantox86.kangfilex.domain.vfs.VfsProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import kotlin.random.Random

class TransferEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun candidateNameFormatsFilesFoldersAndSpecialNames() {
        assertEquals("file (1).txt", ConflictResolver.candidateName("file.txt", isDirectory = false, index = 1))
        assertEquals("file (2).txt", ConflictResolver.candidateName("file.txt", isDirectory = false, index = 2))
        assertEquals("archive.tar (1).gz", ConflictResolver.candidateName("archive.tar.gz", isDirectory = false, index = 1))
        assertEquals("README (1)", ConflictResolver.candidateName("README", isDirectory = false, index = 1))
        assertEquals(".gitignore (1)", ConflictResolver.candidateName(".gitignore", isDirectory = false, index = 1))
        assertEquals("folder (1)", ConflictResolver.candidateName("folder", isDirectory = true, index = 1))
        assertEquals("folder.v1 (3)", ConflictResolver.candidateName("folder.v1", isDirectory = true, index = 3))
    }

    @Test
    fun copySingleFileByteForByte() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val provider = LocalVfsProvider(ioDispatcher = dispatcher)
        val engine = TransferEngine(sourceProvider = provider, ioDispatcher = dispatcher)

        val srcDir = tempFolder.newFolder("src")
        val dstDir = tempFolder.newFolder("dst")
        val payload = "Halo KangFileX Phase 2!".toByteArray()
        val srcFile = File(srcDir, "hello.txt").apply { writeBytes(payload) }

        val result = engine.execute(
            request = TransferRequest(
                jobId = "job-1",
                operation = TransferOperation.COPY,
                sources = listOf(toVfsFile(srcFile)),
                destDirPath = dstDir.absolutePath
            ),
            onConflict = { ConflictDecision(ConflictAction.OVERWRITE) }
        )

        assertEquals(1, result.filesTransferred)
        assertEquals(0, result.filesSkipped)
        assertEquals(payload.size.toLong(), result.bytesTransferred)
        assertTrue(result.errors.isEmpty())

        val copied = File(dstDir, "hello.txt")
        assertTrue(srcFile.exists())
        assertTrue(copied.exists())
        assertArrayEquals(payload, copied.readBytes())
    }

    @Test
    fun copyNestedDirectoryPreservesContentAndEmptyFolders() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val provider = LocalVfsProvider(ioDispatcher = dispatcher)
        val engine = TransferEngine(sourceProvider = provider, ioDispatcher = dispatcher)

        val srcRoot = tempFolder.newFolder("src_nested")
        val projectDir = File(srcRoot, "project").apply { mkdirs() }
        val fileA = File(projectDir, "a.bin").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val nestedDir = File(projectDir, "sub/deep").apply { mkdirs() }
        val fileB = File(nestedDir, "b.bin").apply { writeBytes(byteArrayOf(9, 8, 7, 6, 5)) }
        File(projectDir, "empty_dir").mkdirs()

        val dstRoot = tempFolder.newFolder("dst_nested")

        val result = engine.execute(
            request = TransferRequest(
                jobId = "job-nested",
                operation = TransferOperation.COPY,
                sources = listOf(toVfsFile(projectDir)),
                destDirPath = dstRoot.absolutePath
            ),
            onConflict = { ConflictDecision(ConflictAction.OVERWRITE) }
        )

        assertEquals(2, result.filesTransferred)
        assertEquals(0, result.filesSkipped)
        assertEquals(9L, result.bytesTransferred)
        assertTrue(result.errors.isEmpty())

        assertArrayEquals(fileA.readBytes(), File(dstRoot, "project/a.bin").readBytes())
        assertArrayEquals(fileB.readBytes(), File(dstRoot, "project/sub/deep/b.bin").readBytes())
        val copiedEmptyDir = File(dstRoot, "project/empty_dir")
        assertTrue(copiedEmptyDir.exists() && copiedEmptyDir.isDirectory)
    }

    @Test
    fun copyLargeFileAcrossMultipleBuffersIsByteIdentical() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val provider = LocalVfsProvider(ioDispatcher = dispatcher)
        var fakeClock = 1_000L
        val engine = TransferEngine(
            sourceProvider = provider,
            bufferSize = 16 * 1024,
            ioDispatcher = dispatcher,
            nowMillis = {
                fakeClock += 50L
                fakeClock
            }
        )

        val srcDir = tempFolder.newFolder("src_large")
        val dstDir = tempFolder.newFolder("dst_large")
        val sizeBytes = 1024 * 1024 // 1 MB deterministic random bytes
        val payload = Random(42).nextBytes(sizeBytes)
        val srcFile = File(srcDir, "large.bin").apply { writeBytes(payload) }

        val progressEvents = mutableListOf<TransferProgress>()
        val result = engine.execute(
            request = TransferRequest(
                jobId = "job-large",
                operation = TransferOperation.COPY,
                sources = listOf(toVfsFile(srcFile)),
                destDirPath = dstDir.absolutePath
            ),
            onProgress = { progressEvents.add(it) },
            onConflict = { ConflictDecision(ConflictAction.OVERWRITE) }
        )

        assertEquals(1, result.filesTransferred)
        assertEquals(sizeBytes.toLong(), result.bytesTransferred)
        assertTrue(result.errors.isEmpty())
        assertArrayEquals(payload, File(dstDir, "large.bin").readBytes())

        assertTrue(progressEvents.isNotEmpty())
        val lastProgress = progressEvents.last()
        assertEquals(sizeBytes.toLong(), lastProgress.totalBytes)
        assertEquals(sizeBytes.toLong(), lastProgress.bytesTransferred)
        assertEquals(1, lastProgress.filesCompleted)
        assertEquals(1, lastProgress.totalFiles)
        assertTrue(lastProgress.speedBytesPerSec > 0L)
    }

    @Test
    fun moveFastPathRenameRemovesSourceAndPreservesContent() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val provider = LocalVfsProvider(ioDispatcher = dispatcher)
        val engine = TransferEngine(sourceProvider = provider, ioDispatcher = dispatcher)

        val srcDir = tempFolder.newFolder("src_move_fast")
        val dstDir = tempFolder.newFolder("dst_move_fast")
        val payload = "Move fast path content".toByteArray()
        val srcFile = File(srcDir, "fast.txt").apply { writeBytes(payload) }

        val progressEvents = mutableListOf<TransferProgress>()
        val result = engine.execute(
            request = TransferRequest(
                jobId = "job-move-fast",
                operation = TransferOperation.MOVE,
                sources = listOf(toVfsFile(srcFile)),
                destDirPath = dstDir.absolutePath
            ),
            onProgress = { progressEvents.add(it) },
            onConflict = { ConflictDecision(ConflictAction.OVERWRITE) }
        )

        assertEquals(1, result.filesTransferred)
        assertEquals(payload.size.toLong(), result.bytesTransferred)
        assertTrue(result.errors.isEmpty())
        assertFalse(srcFile.exists())
        assertArrayEquals(payload, File(dstDir, "fast.txt").readBytes())
        assertEquals(payload.size.toLong(), progressEvents.last().bytesTransferred)
        assertEquals(1, progressEvents.last().filesCompleted)
    }

    @Test
    fun moveFallbackStreamingWhenRenameFailsCopiesVerifiesAndDeletesSource() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val local = LocalVfsProvider(ioDispatcher = dispatcher)
        val renameFailingProvider = RenameFailingVfsProvider(local)
        val engine = TransferEngine(
            sourceProvider = renameFailingProvider,
            destProvider = renameFailingProvider,
            bufferSize = 1024,
            ioDispatcher = dispatcher
        )

        val srcDir = tempFolder.newFolder("src_move_fallback")
        val dstDir = tempFolder.newFolder("dst_move_fallback")
        val folder = File(srcDir, "docs").apply { mkdirs() }
        val payload = Random(7).nextBytes(4096)
        val innerFile = File(folder, "doc.bin").apply { writeBytes(payload) }

        val result = engine.execute(
            request = TransferRequest(
                jobId = "job-move-fallback",
                operation = TransferOperation.MOVE,
                sources = listOf(toVfsFile(folder)),
                destDirPath = dstDir.absolutePath
            ),
            onConflict = { ConflictDecision(ConflictAction.OVERWRITE) }
        )

        assertTrue(renameFailingProvider.renameAttempts > 0)
        assertEquals(1, result.filesTransferred)
        assertEquals(payload.size.toLong(), result.bytesTransferred)
        assertTrue(result.errors.isEmpty())
        assertFalse(folder.exists())
        assertFalse(innerFile.exists())
        assertArrayEquals(payload, File(dstDir, "docs/doc.bin").readBytes())
    }

    @Test
    fun conflictOverwriteReplacesDestinationContent() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val provider = LocalVfsProvider(ioDispatcher = dispatcher)
        val engine = TransferEngine(sourceProvider = provider, ioDispatcher = dispatcher)

        val srcDir = tempFolder.newFolder("src_overwrite")
        val dstDir = tempFolder.newFolder("dst_overwrite")
        val srcFile = File(srcDir, "config.txt").apply { writeText("BARU") }
        val dstFile = File(dstDir, "config.txt").apply { writeText("LAMA") }

        var capturedConflict: ConflictInfo? = null
        val result = engine.execute(
            request = TransferRequest(
                jobId = "job-overwrite",
                operation = TransferOperation.COPY,
                sources = listOf(toVfsFile(srcFile)),
                destDirPath = dstDir.absolutePath
            ),
            onConflict = { info ->
                capturedConflict = info
                ConflictDecision(ConflictAction.OVERWRITE)
            }
        )

        assertNotNull(capturedConflict)
        assertNotNull(capturedConflict?.existingDest)
        assertEquals("config.txt", capturedConflict?.existingDest?.name)
        assertEquals(1, result.filesTransferred)
        assertEquals(0, result.filesSkipped)
        assertEquals("BARU", dstFile.readText())
    }

    @Test
    fun conflictSkipKeepsDestinationAndDoesNotDeleteMoveSource() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val provider = LocalVfsProvider(ioDispatcher = dispatcher)
        val engine = TransferEngine(sourceProvider = provider, ioDispatcher = dispatcher)

        val srcDir = tempFolder.newFolder("src_skip")
        val dstDir = tempFolder.newFolder("dst_skip")
        val srcFile = File(srcDir, "note.txt").apply { writeText("SUMBER") }
        val dstFile = File(dstDir, "note.txt").apply { writeText("TUJUAN") }

        val progressEvents = mutableListOf<TransferProgress>()
        val result = engine.execute(
            request = TransferRequest(
                jobId = "job-skip",
                operation = TransferOperation.MOVE,
                sources = listOf(toVfsFile(srcFile)),
                destDirPath = dstDir.absolutePath
            ),
            onProgress = { progressEvents.add(it) },
            onConflict = { ConflictDecision(ConflictAction.SKIP) }
        )

        assertEquals(0, result.filesTransferred)
        assertEquals(1, result.filesSkipped)
        assertTrue(result.errors.isEmpty())
        assertTrue(srcFile.exists())
        assertEquals("SUMBER", srcFile.readText())
        assertEquals("TUJUAN", dstFile.readText())

        val finalProgress = progressEvents.last()
        assertEquals(finalProgress.totalFiles, finalProgress.filesCompleted)
        assertEquals(finalProgress.totalBytes, finalProgress.bytesTransferred)
    }

    @Test
    fun conflictAutoRenameIncrementsSequenceAndPreservesExistingFiles() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val provider = LocalVfsProvider(ioDispatcher = dispatcher)
        val engine = TransferEngine(sourceProvider = provider, ioDispatcher = dispatcher)

        val srcDir = tempFolder.newFolder("src_autorename")
        val dstDir = tempFolder.newFolder("dst_autorename")
        val srcFile = File(srcDir, "file.txt").apply { writeText("V2") }
        val existingFile = File(dstDir, "file.txt").apply { writeText("V1") }

        val request = TransferRequest(
            jobId = "job-autorename-1",
            operation = TransferOperation.COPY,
            sources = listOf(toVfsFile(srcFile)),
            destDirPath = dstDir.absolutePath
        )

        val result1 = engine.execute(request) { ConflictDecision(ConflictAction.AUTO_RENAME) }
        assertEquals(1, result1.filesTransferred)
        assertEquals("V1", existingFile.readText())
        assertEquals("V2", File(dstDir, "file (1).txt").readText())

        srcFile.writeText("V3")
        val result2 = engine.execute(
            request.copy(jobId = "job-autorename-2", sources = listOf(toVfsFile(srcFile)))
        ) { ConflictDecision(ConflictAction.AUTO_RENAME) }
        assertEquals(1, result2.filesTransferred)
        assertEquals("V1", existingFile.readText())
        assertEquals("V2", File(dstDir, "file (1).txt").readText())
        assertEquals("V3", File(dstDir, "file (2).txt").readText())
    }

    @Test
    fun conflictApplyToAllInvokesCallbackOnlyOnceForMultipleConflicts() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val provider = LocalVfsProvider(ioDispatcher = dispatcher)
        val engine = TransferEngine(sourceProvider = provider, ioDispatcher = dispatcher)

        val srcDir = tempFolder.newFolder("src_applyall")
        val dstDir = tempFolder.newFolder("dst_applyall")
        val files = listOf("a.txt", "b.txt", "c.txt").map { name ->
            File(dstDir, name).writeText("OLD_$name")
            File(srcDir, name).apply { writeText("NEW_$name") }
        }

        var conflictPromptCount = 0
        val result = engine.execute(
            request = TransferRequest(
                jobId = "job-applyall",
                operation = TransferOperation.COPY,
                sources = files.map { toVfsFile(it) },
                destDirPath = dstDir.absolutePath
            ),
            onConflict = {
                conflictPromptCount++
                ConflictDecision(ConflictAction.OVERWRITE, applyToAll = true)
            }
        )

        assertEquals(1, conflictPromptCount)
        assertEquals(3, result.filesTransferred)
        assertEquals("NEW_a.txt", File(dstDir, "a.txt").readText())
        assertEquals("NEW_b.txt", File(dstDir, "b.txt").readText())
        assertEquals("NEW_c.txt", File(dstDir, "c.txt").readText())
    }

    @Test
    fun selfCopyAndMoveToSubfolderThrowsTransferExceptionWithoutCreatingFolders() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val provider = LocalVfsProvider(ioDispatcher = dispatcher)
        val engine = TransferEngine(sourceProvider = provider, ioDispatcher = dispatcher)

        val folder = tempFolder.newFolder("self_guard")
        File(folder, "child.txt").writeText("data")
        val vfsFolder = toVfsFile(folder)

        // 1. Self-copy
        try {
            engine.execute(
                TransferRequest("job-self", TransferOperation.COPY, listOf(vfsFolder), folder.absolutePath)
            ) { ConflictDecision(ConflictAction.OVERWRITE) }
            fail("Expected TransferException on self-copy")
        } catch (_: TransferException) {
            // expected
        }

        // 2. Copy to non-existent subfolder must fail BEFORE creating folder
        val nonExistentSub = File(folder, "new_sub")
        try {
            engine.execute(
                TransferRequest("job-sub-copy", TransferOperation.COPY, listOf(vfsFolder), nonExistentSub.absolutePath)
            ) { ConflictDecision(ConflictAction.OVERWRITE) }
            fail("Expected TransferException on copy to subfolder")
        } catch (_: TransferException) {
            // expected
        }
        assertFalse(nonExistentSub.exists())

        // 3. Move to existing subfolder
        val existingSub = File(folder, "existing_sub").apply { mkdirs() }
        try {
            engine.execute(
                TransferRequest("job-sub-move", TransferOperation.MOVE, listOf(vfsFolder), existingSub.absolutePath)
            ) { ConflictDecision(ConflictAction.OVERWRITE) }
            fail("Expected TransferException on move to subfolder")
        } catch (_: TransferException) {
            // expected
        }
        assertTrue(folder.exists())
        assertTrue(File(folder, "child.txt").exists())
    }

    @Test
    fun cancelDuringStreamingDeletesPartialFileAndPreservesCompletedFile() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val local = LocalVfsProvider(ioDispatcher = dispatcher)

        val srcDir = tempFolder.newFolder("src_cancel")
        val dstDir = tempFolder.newFolder("dst_cancel")
        val firstPayload = Random(1).nextBytes(8 * 1024)
        val secondPayload = Random(2).nextBytes(32 * 1024)
        val firstFile = File(srcDir, "first.bin").apply { writeBytes(firstPayload) }
        val secondFile = File(srcDir, "second.bin").apply { writeBytes(secondPayload) }

        var transferJob: Job? = null
        val cancelingDestProvider = CancelingOnWriteVfsProvider(
            delegate = local,
            targetFileName = "second.bin",
            cancelAfterWrites = 2,
            onTriggerCancel = { transferJob?.cancel() }
        )

        val engine = TransferEngine(
            sourceProvider = local,
            destProvider = cancelingDestProvider,
            bufferSize = 4 * 1024,
            ioDispatcher = dispatcher
        )

        var caughtCancellation = false
        transferJob = launch(dispatcher) {
            try {
                engine.execute(
                    request = TransferRequest(
                        jobId = "job-cancel",
                        operation = TransferOperation.COPY,
                        sources = listOf(toVfsFile(firstFile), toVfsFile(secondFile)),
                        destDirPath = dstDir.absolutePath
                    ),
                    onConflict = { ConflictDecision(ConflictAction.OVERWRITE) }
                )
            } catch (ce: CancellationException) {
                caughtCancellation = true
                throw ce
            }
        }

        transferJob.join()

        assertTrue(caughtCancellation)
        assertTrue(transferJob.isCancelled)
        val completedFirst = File(dstDir, "first.bin")
        val partialSecond = File(dstDir, "second.bin")
        assertTrue(completedFirst.exists())
        assertArrayEquals(firstPayload, completedFirst.readBytes())
        assertFalse(partialSecond.exists())
    }

    @Test
    fun emptySourcesThrowsTransferException() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val provider = LocalVfsProvider(ioDispatcher = dispatcher)
        val engine = TransferEngine(sourceProvider = provider, ioDispatcher = dispatcher)
        val dstDir = tempFolder.newFolder("dst_empty")

        try {
            engine.execute(
                TransferRequest(
                    jobId = "job-empty",
                    operation = TransferOperation.COPY,
                    sources = emptyList(),
                    destDirPath = dstDir.absolutePath
                ),
                onConflict = { ConflictDecision(ConflictAction.OVERWRITE) }
            )
            fail("Expected TransferException for empty sources")
        } catch (_: TransferException) {
            // expected
        }
    }

    @Test
    fun copySymlinkDirectorySkipsWithErrorAndLeavesTargetIntact() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val provider = LocalVfsProvider(ioDispatcher = dispatcher)
        val engine = TransferEngine(sourceProvider = provider, ioDispatcher = dispatcher)

        val srcRoot = tempFolder.newFolder("src_symlink_copy")
        val targetDir = tempFolder.newFolder("target_symlink_copy")
        val targetFile = File(targetDir, "inside.txt").apply { writeText("TARGET_DATA") }
        val symlinkDir = File(srcRoot, "link_dir")
        createSymbolicLinkOrSkip(symlinkDir, targetDir)

        val dstDir = tempFolder.newFolder("dst_symlink_copy")
        val symlinkVfs = provider.listFiles(srcRoot.absolutePath)
            .getOrThrow()
            .first { it.name == "link_dir" }
        assertTrue(symlinkVfs.isDirectory)
        assertTrue(symlinkVfs.isSymlink)

        val result = engine.execute(
            request = TransferRequest(
                jobId = "job-symlink-copy",
                operation = TransferOperation.COPY,
                sources = listOf(symlinkVfs),
                destDirPath = dstDir.absolutePath
            ),
            onConflict = { ConflictDecision(ConflictAction.OVERWRITE) }
        )

        assertEquals(0, result.filesTransferred)
        assertEquals(1, result.filesSkipped)
        assertEquals(1, result.errors.size)
        assertTrue(result.errors.first().contains("symlink", ignoreCase = true))

        val destEntry = File(dstDir, "link_dir")
        assertFalse(destEntry.exists())
        assertFalse(Files.isSymbolicLink(destEntry.toPath()))
        assertTrue(targetDir.exists())
        assertEquals("TARGET_DATA", targetFile.readText())
    }

    @Test
    fun moveSymlinkDirectorySkipsWithErrorAndPreservesSourceSymlink() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val provider = LocalVfsProvider(ioDispatcher = dispatcher)
        val engine = TransferEngine(sourceProvider = provider, ioDispatcher = dispatcher)

        val srcRoot = tempFolder.newFolder("src_symlink_move")
        val targetDir = tempFolder.newFolder("target_symlink_move")
        val targetFile = File(targetDir, "inside.txt").apply { writeText("TARGET_MOVE") }
        val symlinkDir = File(srcRoot, "link_dir")
        createSymbolicLinkOrSkip(symlinkDir, targetDir)

        val dstDir = tempFolder.newFolder("dst_symlink_move")
        val symlinkVfs = provider.listFiles(srcRoot.absolutePath)
            .getOrThrow()
            .first { it.name == "link_dir" }
        assertTrue(symlinkVfs.isDirectory)
        assertTrue(symlinkVfs.isSymlink)

        val result = engine.execute(
            request = TransferRequest(
                jobId = "job-symlink-move",
                operation = TransferOperation.MOVE,
                sources = listOf(symlinkVfs),
                destDirPath = dstDir.absolutePath
            ),
            onConflict = { ConflictDecision(ConflictAction.OVERWRITE) }
        )

        assertEquals(0, result.filesTransferred)
        assertEquals(1, result.filesSkipped)
        assertTrue(result.errors.isNotEmpty())
        assertTrue(result.errors.first().contains("symlink", ignoreCase = true))
        assertTrue(Files.isSymbolicLink(symlinkDir.toPath()))

        val destEntry = File(dstDir, "link_dir")
        assertFalse(destEntry.exists())
        assertFalse(Files.isSymbolicLink(destEntry.toPath()))
        assertEquals("TARGET_MOVE", targetFile.readText())
    }

    @Test
    fun copyDirectoryContainingSymlinkDirCopiesRegularFilesAndSkipsSymlink() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val provider = LocalVfsProvider(ioDispatcher = dispatcher)
        val engine = TransferEngine(sourceProvider = provider, ioDispatcher = dispatcher)

        val srcRoot = tempFolder.newFolder("src_mixed_symlink")
        val outerDir = File(srcRoot, "outer").apply { mkdirs() }
        File(outerDir, "normal.txt").writeText("NORMAL_CONTENT")

        val targetDir = tempFolder.newFolder("target_mixed_symlink")
        File(targetDir, "secret.txt").writeText("DO_NOT_COPY")
        val nestedSymlink = File(outerDir, "nested_link")
        createSymbolicLinkOrSkip(nestedSymlink, targetDir)

        val dstDir = tempFolder.newFolder("dst_mixed_symlink")
        val outerVfs = provider.listFiles(srcRoot.absolutePath)
            .getOrThrow()
            .first { it.name == "outer" }

        val result = engine.execute(
            request = TransferRequest(
                jobId = "job-mixed-symlink",
                operation = TransferOperation.COPY,
                sources = listOf(outerVfs),
                destDirPath = dstDir.absolutePath
            ),
            onConflict = { ConflictDecision(ConflictAction.OVERWRITE) }
        )

        assertEquals(1, result.filesTransferred)
        assertEquals(1, result.filesSkipped)
        assertEquals(1, result.errors.size)
        assertTrue(result.errors.first().contains("symlink", ignoreCase = true))
        assertEquals("NORMAL_CONTENT", File(dstDir, "outer/normal.txt").readText())

        val copiedLink = File(dstDir, "outer/nested_link")
        assertFalse(copiedLink.exists())
        assertFalse(Files.isSymbolicLink(copiedLink.toPath()))
    }

    @Test
    fun overwriteFolderOntoExistingFileRecordsSingleErrorAndLeavesBothIntact() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val provider = LocalVfsProvider(ioDispatcher = dispatcher)
        val engine = TransferEngine(sourceProvider = provider, ioDispatcher = dispatcher)

        val srcDir = tempFolder.newFolder("src_mismatch_dir_to_file")
        val dstDir = tempFolder.newFolder("dst_mismatch_dir_to_file")

        val srcFolder = File(srcDir, "x").apply { mkdirs() }
        val srcFile1 = File(srcFolder, "f1.txt").apply { writeText("ONE") }
        val srcFile2 = File(srcFolder, "f2.txt").apply { writeText("TWO") }
        val dstFile = File(dstDir, "x").apply { writeText("EXISTING_FILE") }

        val result = engine.execute(
            request = TransferRequest(
                jobId = "job-mismatch-dir-to-file",
                operation = TransferOperation.COPY,
                sources = listOf(toVfsFile(srcFolder)),
                destDirPath = dstDir.absolutePath
            ),
            onConflict = { ConflictDecision(ConflictAction.OVERWRITE) }
        )

        assertEquals(0, result.filesTransferred)
        assertEquals(0, result.filesSkipped)
        assertEquals(1, result.errors.size)
        val errorMsg = result.errors.first()
        assertTrue(errorMsg.contains("folder", ignoreCase = true))
        assertTrue(errorMsg.contains("file", ignoreCase = true))

        assertTrue(dstFile.isFile)
        assertEquals("EXISTING_FILE", dstFile.readText())
        assertTrue(srcFolder.isDirectory)
        assertEquals("ONE", srcFile1.readText())
        assertEquals("TWO", srcFile2.readText())
    }

    @Test
    fun overwriteFileOntoExistingFolderRecordsSingleErrorAndLeavesFolderIntact() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val provider = LocalVfsProvider(ioDispatcher = dispatcher)
        val engine = TransferEngine(sourceProvider = provider, ioDispatcher = dispatcher)

        val srcDir = tempFolder.newFolder("src_mismatch_file_to_dir")
        val dstDir = tempFolder.newFolder("dst_mismatch_file_to_dir")

        val srcFile = File(srcDir, "y").apply { writeText("SOURCE_FILE") }
        val dstFolder = File(dstDir, "y").apply { mkdirs() }
        val dstInner = File(dstFolder, "keep.txt").apply { writeText("KEEP_ME") }

        val result = engine.execute(
            request = TransferRequest(
                jobId = "job-mismatch-file-to-dir",
                operation = TransferOperation.COPY,
                sources = listOf(toVfsFile(srcFile)),
                destDirPath = dstDir.absolutePath
            ),
            onConflict = { ConflictDecision(ConflictAction.OVERWRITE) }
        )

        assertEquals(0, result.filesTransferred)
        assertEquals(0, result.filesSkipped)
        assertEquals(1, result.errors.size)
        val errorMsg = result.errors.first()
        assertTrue(errorMsg.contains("file", ignoreCase = true))
        assertTrue(errorMsg.contains("folder", ignoreCase = true))

        assertTrue(dstFolder.isDirectory)
        assertTrue(dstInner.exists())
        assertEquals("KEEP_ME", dstInner.readText())
        assertTrue(srcFile.isFile)
        assertEquals("SOURCE_FILE", srcFile.readText())
    }

    @Test
    fun siblingPrefixDirectoryIsAllowedWhileTrueSubfolderThrowsTransferException() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val provider = LocalVfsProvider(ioDispatcher = dispatcher)
        val engine = TransferEngine(sourceProvider = provider, ioDispatcher = dispatcher)

        val root = tempFolder.newFolder("a")
        val dirB = File(root, "b").apply { mkdirs() }
        val fileInB = File(dirB, "item.txt").apply { writeText("konten-b") }
        val dirBc = File(root, "bc").apply { mkdirs() }
        val vfsDirB = toVfsFile(dirB)

        // Sibling with shared prefix (/a/b -> /a/bc) must be allowed
        val result = engine.execute(
            request = TransferRequest(
                jobId = "job-sibling-prefix",
                operation = TransferOperation.COPY,
                sources = listOf(vfsDirB),
                destDirPath = dirBc.absolutePath
            ),
            onConflict = { ConflictDecision(ConflictAction.OVERWRITE) }
        )

        assertEquals(1, result.filesTransferred)
        assertTrue(result.errors.isEmpty())
        val copiedFile = File(dirBc, "b/item.txt")
        assertTrue(copiedFile.exists())
        assertEquals("konten-b", copiedFile.readText())
        assertTrue(fileInB.exists())

        // True subfolder (/a/b -> /a/b/c) must be rejected with TransferException
        val subC = File(dirB, "c")
        try {
            engine.execute(
                request = TransferRequest(
                    jobId = "job-true-subfolder",
                    operation = TransferOperation.COPY,
                    sources = listOf(vfsDirB),
                    destDirPath = subC.absolutePath
                ),
                onConflict = { ConflictDecision(ConflictAction.OVERWRITE) }
            )
            fail("Expected TransferException on copy to true subfolder")
        } catch (_: TransferException) {
            // expected
        }
        assertFalse(subC.exists())
    }

    private fun createSymbolicLinkOrSkip(link: File, target: File) {
        val created = try {
            Files.createSymbolicLink(link.toPath(), target.toPath())
            true
        } catch (_: UnsupportedOperationException) {
            false
        } catch (_: SecurityException) {
            false
        }
        Assume.assumeTrue("Filesystem does not support symbolic links", created)
    }

    private fun toVfsFile(file: File, providerId: String = LocalVfsProvider.DEFAULT_PROVIDER_ID): VfsFile {
        return VfsFile(
            name = file.name,
            path = file.absolutePath,
            isDirectory = file.isDirectory,
            size = if (file.isDirectory) 0L else file.length(),
            lastModified = file.lastModified(),
            providerId = providerId
        )
    }

    private class RenameFailingVfsProvider(
        private val delegate: VfsProvider
    ) : VfsProvider by delegate {
        var renameAttempts: Int = 0
            private set

        override suspend fun rename(oldPath: String, newPath: String): Result<Unit> {
            renameAttempts++
            return Result.failure(IOException("Simulated cross-volume rename failure"))
        }
    }

    private class CancelingOnWriteVfsProvider(
        private val delegate: VfsProvider,
        private val targetFileName: String,
        private val cancelAfterWrites: Int,
        private val onTriggerCancel: () -> Unit
    ) : VfsProvider by delegate {
        override suspend fun openOutputStream(path: String, overwrite: Boolean): OutputStream {
            val raw = delegate.openOutputStream(path, overwrite)
            if (!path.endsWith("/$targetFileName")) {
                return raw
            }
            return object : FilterOutputStream(raw) {
                private var writeCount = 0

                override fun write(b: ByteArray, off: Int, len: Int) {
                    out.write(b, off, len)
                    writeCount++
                    if (writeCount == cancelAfterWrites) {
                        onTriggerCancel()
                    }
                }
            }
        }
    }
}
