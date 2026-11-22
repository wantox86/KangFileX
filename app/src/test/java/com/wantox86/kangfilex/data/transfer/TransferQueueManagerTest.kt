package com.wantox86.kangfilex.data.transfer

import com.wantox86.kangfilex.data.local.LocalVfsProvider
import com.wantox86.kangfilex.domain.model.VfsFile
import com.wantox86.kangfilex.domain.transfer.ConflictAction
import com.wantox86.kangfilex.domain.transfer.ConflictDecision
import com.wantox86.kangfilex.domain.transfer.TransferOperation
import com.wantox86.kangfilex.domain.transfer.TransferRequest
import com.wantox86.kangfilex.domain.vfs.VfsProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TransferQueueManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun singleJobCompletesAndReturnsToIdle() = runBlocking {
        val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val provider = LocalVfsProvider(ioDispatcher = Dispatchers.IO)
        val manager = TransferQueueManager(
            vfsProvider = provider,
            scope = managerScope,
            ioDispatcher = Dispatchers.IO
        )
        val outcomes = collectOutcomes(managerScope, manager)

        try {
            val srcDir = tempFolder.newFolder("src_single")
            val dstDir = tempFolder.newFolder("dst_single")
            val payload = "Halo TransferQueueManager".toByteArray()
            val srcFile = File(srcDir, "doc.txt").apply { writeBytes(payload) }

            manager.enqueue(
                TransferRequest(
                    jobId = "job-1",
                    operation = TransferOperation.COPY,
                    sources = listOf(toVfsFile(srcFile)),
                    destDirPath = dstDir.absolutePath
                )
            )

            val outcome = withTimeout(5_000) { outcomes.receive() }
            assertTrue(outcome is TransferOutcome.Completed)
            val completed = outcome as TransferOutcome.Completed
            assertEquals("job-1", completed.jobId)
            assertEquals(TransferOperation.COPY, completed.operation)
            assertEquals(1, completed.result.filesTransferred)
            assertEquals(payload.size.toLong(), completed.result.bytesTransferred)

            val idleState = withTimeout(5_000) {
                manager.state.first { !it.isBusy }
            }
            assertNull(idleState.activeJob)
            assertEquals(0, idleState.queuedCount)
            assertNull(idleState.pendingConflict)
            assertFalse(idleState.isProgressDialogVisible)
            assertArrayEquals(payload, File(dstDir, "doc.txt").readBytes())
        } finally {
            managerScope.cancel()
        }
    }

    @Test
    fun twoJobsExecuteSequentiallyInFifoOrder() = runBlocking {
        val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val local = LocalVfsProvider(ioDispatcher = Dispatchers.IO)
        val firstWriteStarted = CountDownLatch(1)
        val allowFirstFinish = CountDownLatch(1)
        val gatedProvider = GatedWriteVfsProvider(
            delegate = local,
            targetFileName = "first.bin",
            onWriteChunk = { chunkIndex ->
                if (chunkIndex == 1) {
                    firstWriteStarted.countDown()
                    allowFirstFinish.await(5, TimeUnit.SECONDS)
                }
            }
        )
        val manager = TransferQueueManager(
            vfsProvider = gatedProvider,
            scope = managerScope,
            ioDispatcher = Dispatchers.IO,
            engineBufferSize = 1024
        )
        val outcomes = collectOutcomes(managerScope, manager)

        try {
            val srcDir = tempFolder.newFolder("src_fifo")
            val dstDir = tempFolder.newFolder("dst_fifo")
            val file1 = File(srcDir, "first.bin").apply { writeBytes(ByteArray(4096) { 1 }) }
            val file2 = File(srcDir, "second.txt").apply { writeText("kedua") }

            manager.enqueue(
                TransferRequest(
                    jobId = "job-1",
                    operation = TransferOperation.COPY,
                    sources = listOf(toVfsFile(file1)),
                    destDirPath = dstDir.absolutePath
                )
            )
            assertTrue(firstWriteStarted.await(5, TimeUnit.SECONDS))

            manager.enqueue(
                TransferRequest(
                    jobId = "job-2",
                    operation = TransferOperation.COPY,
                    sources = listOf(toVfsFile(file2)),
                    destDirPath = dstDir.absolutePath
                )
            )

            val busyState = withTimeout(5_000) {
                manager.state.first { it.activeJob?.jobId == "job-1" && it.queuedCount == 1 }
            }
            assertTrue(busyState.isBusy)

            allowFirstFinish.countDown()

            val firstOutcome = withTimeout(5_000) { outcomes.receive() }
            val secondOutcome = withTimeout(5_000) { outcomes.receive() }

            assertTrue(firstOutcome is TransferOutcome.Completed)
            assertEquals("job-1", firstOutcome.jobId)
            assertTrue(secondOutcome is TransferOutcome.Completed)
            assertEquals("job-2", secondOutcome.jobId)

            withTimeout(5_000) { manager.state.first { !it.isBusy } }
            assertTrue(File(dstDir, "first.bin").exists())
            assertEquals("kedua", File(dstDir, "second.txt").readText())
        } finally {
            allowFirstFinish.countDown()
            managerScope.cancel()
        }
    }

    @Test
    fun conflictExposesPendingConflictAndResumesAfterResolve() = runBlocking {
        val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val provider = LocalVfsProvider(ioDispatcher = Dispatchers.IO)
        val manager = TransferQueueManager(
            vfsProvider = provider,
            scope = managerScope,
            ioDispatcher = Dispatchers.IO
        )
        val outcomes = collectOutcomes(managerScope, manager)

        try {
            val srcDir = tempFolder.newFolder("src_conflict")
            val dstDir = tempFolder.newFolder("dst_conflict")
            val srcFile = File(srcDir, "report.txt").apply { writeText("BARU") }
            val existingDest = File(dstDir, "report.txt").apply { writeText("LAMA") }

            manager.enqueue(
                TransferRequest(
                    jobId = "job-conflict",
                    operation = TransferOperation.COPY,
                    sources = listOf(toVfsFile(srcFile)),
                    destDirPath = dstDir.absolutePath
                )
            )

            val conflictState = withTimeout(5_000) {
                manager.state.first { it.pendingConflict != null }
            }
            val conflictInfo = conflictState.pendingConflict
            assertNotNull(conflictInfo)
            assertEquals("report.txt", conflictInfo?.sourceFile?.name)
            assertNotNull(conflictInfo?.existingDest)

            manager.resolveConflict(ConflictDecision(ConflictAction.AUTO_RENAME))

            val outcome = withTimeout(5_000) { outcomes.receive() }
            assertTrue(outcome is TransferOutcome.Completed)
            assertEquals(1, (outcome as TransferOutcome.Completed).result.filesTransferred)

            val idleState = withTimeout(5_000) { manager.state.first { !it.isBusy } }
            assertNull(idleState.pendingConflict)
            assertEquals("LAMA", existingDest.readText())
            assertEquals("BARU", File(dstDir, "report (1).txt").readText())
        } finally {
            managerScope.cancel()
        }
    }

    @Test
    fun cancelActiveEmitsCancelledCleansPartialFileAndContinuesQueue() = runBlocking {
        val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val local = LocalVfsProvider(ioDispatcher = Dispatchers.IO)
        val firstChunkWritten = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val gatedProvider = GatedWriteVfsProvider(
            delegate = local,
            targetFileName = "slow.bin",
            onWriteChunk = { chunkIndex ->
                if (chunkIndex == 1) {
                    firstChunkWritten.countDown()
                    releaseWrite.await(5, TimeUnit.SECONDS)
                }
            }
        )
        val manager = TransferQueueManager(
            vfsProvider = gatedProvider,
            scope = managerScope,
            ioDispatcher = Dispatchers.IO,
            engineBufferSize = 1024
        )
        val outcomes = collectOutcomes(managerScope, manager)

        try {
            val srcDir = tempFolder.newFolder("src_cancel")
            val dstDir = tempFolder.newFolder("dst_cancel")
            val slowFile = File(srcDir, "slow.bin").apply { writeBytes(ByteArray(8 * 1024) { 7 }) }
            val nextFile = File(srcDir, "next.txt").apply { writeText("tetap-jalan") }

            manager.enqueue(
                TransferRequest(
                    jobId = "job-cancel",
                    operation = TransferOperation.COPY,
                    sources = listOf(toVfsFile(slowFile)),
                    destDirPath = dstDir.absolutePath
                )
            )
            assertTrue(firstChunkWritten.await(5, TimeUnit.SECONDS))

            manager.enqueue(
                TransferRequest(
                    jobId = "job-next",
                    operation = TransferOperation.COPY,
                    sources = listOf(toVfsFile(nextFile)),
                    destDirPath = dstDir.absolutePath
                )
            )

            // Partial file exists mid-stream before cancellation
            assertTrue(File(dstDir, "slow.bin").exists())

            manager.cancelActive()
            releaseWrite.countDown()

            val firstOutcome = withTimeout(5_000) { outcomes.receive() }
            val secondOutcome = withTimeout(5_000) { outcomes.receive() }

            assertTrue(firstOutcome is TransferOutcome.Cancelled)
            assertEquals("job-cancel", firstOutcome.jobId)

            assertTrue(secondOutcome is TransferOutcome.Completed)
            assertEquals("job-next", secondOutcome.jobId)

            withTimeout(5_000) { manager.state.first { !it.isBusy } }
            assertFalse(File(dstDir, "slow.bin").exists())
            assertEquals("tetap-jalan", File(dstDir, "next.txt").readText())
        } finally {
            releaseWrite.countDown()
            managerScope.cancel()
        }
    }

    @Test
    fun failedJobEmitsFailedAndQueueContinues() = runBlocking {
        val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val provider = LocalVfsProvider(ioDispatcher = Dispatchers.IO)
        val manager = TransferQueueManager(
            vfsProvider = provider,
            scope = managerScope,
            ioDispatcher = Dispatchers.IO
        )
        val outcomes = collectOutcomes(managerScope, manager)

        try {
            val selfDir = tempFolder.newFolder("self_folder")
            File(selfDir, "inside.txt").writeText("isi")
            val srcDir = tempFolder.newFolder("src_after_fail")
            val dstDir = tempFolder.newFolder("dst_after_fail")
            val okFile = File(srcDir, "ok.txt").apply { writeText("sukses") }

            manager.enqueue(
                TransferRequest(
                    jobId = "job-self-copy",
                    operation = TransferOperation.COPY,
                    sources = listOf(toVfsFile(selfDir)),
                    destDirPath = selfDir.absolutePath
                )
            )
            manager.enqueue(
                TransferRequest(
                    jobId = "job-ok",
                    operation = TransferOperation.COPY,
                    sources = listOf(toVfsFile(okFile)),
                    destDirPath = dstDir.absolutePath
                )
            )

            val firstOutcome = withTimeout(5_000) { outcomes.receive() }
            val secondOutcome = withTimeout(5_000) { outcomes.receive() }

            assertTrue(firstOutcome is TransferOutcome.Failed)
            assertEquals("job-self-copy", firstOutcome.jobId)
            assertTrue((firstOutcome as TransferOutcome.Failed).message.isNotBlank())

            assertTrue(secondOutcome is TransferOutcome.Completed)
            assertEquals("job-ok", secondOutcome.jobId)

            withTimeout(5_000) { manager.state.first { !it.isBusy } }
            assertEquals("sukses", File(dstDir, "ok.txt").readText())
        } finally {
            managerScope.cancel()
        }
    }

    @Test
    fun minimizePersistsDuringCurrentJobAndResetsOnNextJobAndCompletion() = runBlocking {
        // Perilaku: setProgressDialogVisible(false) dipertahankan selama job aktif berjalan
        // (termasuk saat progres baru masuk), kembali true saat job berikutnya dimulai,
        // dan menjadi false setelah seluruh antrean selesai.
        val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val local = LocalVfsProvider(ioDispatcher = Dispatchers.IO)

        val job1Chunk1 = CountDownLatch(1)
        val releaseJob1Chunk1 = CountDownLatch(1)
        val job1Chunk2 = CountDownLatch(1)
        val releaseJob1Chunk2 = CountDownLatch(1)
        val job2Chunk1 = CountDownLatch(1)
        val releaseJob2 = CountDownLatch(1)

        val gatedProvider = MultiGatedWriteVfsProvider(
            delegate = local,
            onWriteChunk = { fileName, chunkIndex ->
                when (fileName) {
                    "job1.bin" -> {
                        if (chunkIndex == 1) {
                            job1Chunk1.countDown()
                            releaseJob1Chunk1.await(5, TimeUnit.SECONDS)
                        } else if (chunkIndex == 2) {
                            job1Chunk2.countDown()
                            releaseJob1Chunk2.await(5, TimeUnit.SECONDS)
                        }
                    }
                    "job2.bin" -> {
                        if (chunkIndex == 1) {
                            job2Chunk1.countDown()
                            releaseJob2.await(5, TimeUnit.SECONDS)
                        }
                    }
                }
            }
        )

        val manager = TransferQueueManager(
            vfsProvider = gatedProvider,
            scope = managerScope,
            ioDispatcher = Dispatchers.IO,
            engineBufferSize = 1024
        )
        val outcomes = collectOutcomes(managerScope, manager)

        try {
            val srcDir = tempFolder.newFolder("src_minimize")
            val dstDir = tempFolder.newFolder("dst_minimize")
            val file1 = File(srcDir, "job1.bin").apply { writeBytes(ByteArray(4096) { 3 }) }
            val file2 = File(srcDir, "job2.bin").apply { writeBytes(ByteArray(2048) { 4 }) }

            manager.enqueue(
                TransferRequest(
                    jobId = "job-1",
                    operation = TransferOperation.COPY,
                    sources = listOf(toVfsFile(file1)),
                    destDirPath = dstDir.absolutePath
                )
            )
            assertTrue(job1Chunk1.await(5, TimeUnit.SECONDS))
            assertTrue(manager.state.value.isProgressDialogVisible)

            // Minimize dialog saat job-1 berjalan
            manager.setProgressDialogVisible(false)
            assertFalse(manager.state.value.isProgressDialogVisible)

            // Tambah job-2 ke antrean sambil job-1 masih berjalan; dialog tetap minimized
            manager.enqueue(
                TransferRequest(
                    jobId = "job-2",
                    operation = TransferOperation.COPY,
                    sources = listOf(toVfsFile(file2)),
                    destDirPath = dstDir.absolutePath
                )
            )
            releaseJob1Chunk1.countDown()
            assertTrue(job1Chunk2.await(5, TimeUnit.SECONDS))
            assertEquals("job-1", manager.state.value.activeJob?.jobId)
            assertFalse(manager.state.value.isProgressDialogVisible)

            // Selesaikan job-1 -> job-2 mulai dan dialog kembali terlihat
            releaseJob1Chunk2.countDown()
            assertTrue(job2Chunk1.await(5, TimeUnit.SECONDS))
            assertEquals("job-2", manager.state.value.activeJob?.jobId)
            assertTrue(manager.state.value.isProgressDialogVisible)

            // Selesaikan job-2 -> state idle dan dialog kembali false
            releaseJob2.countDown()
            assertTrue(withTimeout(5_000) { outcomes.receive() } is TransferOutcome.Completed)
            assertTrue(withTimeout(5_000) { outcomes.receive() } is TransferOutcome.Completed)

            val idleState = withTimeout(5_000) { manager.state.first { !it.isBusy } }
            assertFalse(idleState.isProgressDialogVisible)
        } finally {
            releaseJob1Chunk1.countDown()
            releaseJob1Chunk2.countDown()
            releaseJob2.countDown()
            managerScope.cancel()
        }
    }

    private fun collectOutcomes(
        scope: CoroutineScope,
        manager: TransferQueueManager
    ): Channel<TransferOutcome> {
        val channel = Channel<TransferOutcome>(Channel.UNLIMITED)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            manager.outcomes.collect { channel.send(it) }
        }
        return channel
    }

    private fun toVfsFile(file: File): VfsFile {
        return VfsFile(
            name = file.name,
            path = file.absolutePath,
            isDirectory = file.isDirectory,
            size = if (file.isDirectory) 0L else file.length(),
            lastModified = file.lastModified(),
            providerId = LocalVfsProvider.DEFAULT_PROVIDER_ID
        )
    }

    private class GatedWriteVfsProvider(
        private val delegate: VfsProvider,
        private val targetFileName: String,
        private val onWriteChunk: (chunkIndex: Int) -> Unit
    ) : VfsProvider by delegate {
        override suspend fun openOutputStream(path: String, overwrite: Boolean): OutputStream {
            val raw = delegate.openOutputStream(path, overwrite)
            if (!path.endsWith("/$targetFileName")) {
                return raw
            }
            return object : FilterOutputStream(raw) {
                private var chunks = 0

                override fun write(b: ByteArray, off: Int, len: Int) {
                    out.write(b, off, len)
                    chunks++
                    onWriteChunk(chunks)
                }
            }
        }
    }

    private class MultiGatedWriteVfsProvider(
        private val delegate: VfsProvider,
        private val onWriteChunk: (fileName: String, chunkIndex: Int) -> Unit
    ) : VfsProvider by delegate {
        override suspend fun openOutputStream(path: String, overwrite: Boolean): OutputStream {
            val raw = delegate.openOutputStream(path, overwrite)
            val fileName = File(path).name
            return object : FilterOutputStream(raw) {
                private var chunks = 0

                override fun write(b: ByteArray, off: Int, len: Int) {
                    out.write(b, off, len)
                    chunks++
                    onWriteChunk(fileName, chunks)
                }
            }
        }
    }
}
