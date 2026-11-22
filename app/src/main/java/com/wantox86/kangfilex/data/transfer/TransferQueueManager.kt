package com.wantox86.kangfilex.data.transfer

import com.wantox86.kangfilex.data.local.LocalVfsProvider
import com.wantox86.kangfilex.domain.transfer.ConflictDecision
import com.wantox86.kangfilex.domain.transfer.ConflictInfo
import com.wantox86.kangfilex.domain.transfer.TransferEngine
import com.wantox86.kangfilex.domain.transfer.TransferOperation
import com.wantox86.kangfilex.domain.transfer.TransferProgress
import com.wantox86.kangfilex.domain.transfer.TransferRequest
import com.wantox86.kangfilex.domain.transfer.TransferResult
import com.wantox86.kangfilex.domain.vfs.VfsProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class TransferJobUi(
    val jobId: String,
    val operation: TransferOperation,
    val progress: TransferProgress?
)

sealed interface TransferOutcome {
    val jobId: String

    data class Completed(
        override val jobId: String,
        val operation: TransferOperation,
        val result: TransferResult
    ) : TransferOutcome

    data class Failed(
        override val jobId: String,
        val message: String
    ) : TransferOutcome

    data class Cancelled(
        override val jobId: String
    ) : TransferOutcome
}

data class TransferUiState(
    val activeJob: TransferJobUi? = null,
    val queuedCount: Int = 0,
    val pendingConflict: ConflictInfo? = null,
    val isProgressDialogVisible: Boolean = false
) {
    val isBusy: Boolean get() = activeJob != null || queuedCount > 0
}

class TransferQueueManager(
    private val vfsProvider: VfsProvider,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val engineBufferSize: Int = TransferEngine.DEFAULT_BUFFER_SIZE
) {

    private val lock = Any()
    private val queue = ArrayDeque<TransferRequest>()

    private var workerJob: Job? = null
    private var activeExecutionJob: Job? = null
    private var activeCancelRequested: Boolean = false
    private var pendingConflictDeferred: CompletableDeferred<ConflictDecision>? = null

    private val _state = MutableStateFlow(TransferUiState())
    val state: StateFlow<TransferUiState> = _state.asStateFlow()

    private val _outcomes = MutableSharedFlow<TransferOutcome>(
        replay = 0,
        extraBufferCapacity = 32
    )
    val outcomes: SharedFlow<TransferOutcome> = _outcomes.asSharedFlow()

    fun enqueue(request: TransferRequest) {
        synchronized(lock) {
            queue.addLast(request)
            val shouldStartWorker = workerJob == null || workerJob?.isActive != true
            _state.update { current ->
                current.copy(queuedCount = queue.size)
            }
            if (shouldStartWorker) {
                workerJob = scope.launch {
                    processQueue()
                }
            }
        }
    }

    fun cancelActive() {
        val jobToCancel: Job?
        val conflictToCancel: CompletableDeferred<ConflictDecision>?
        synchronized(lock) {
            if (workerJob == null && activeExecutionJob == null && _state.value.activeJob == null) {
                return
            }
            activeCancelRequested = true
            jobToCancel = activeExecutionJob
            conflictToCancel = pendingConflictDeferred
            pendingConflictDeferred = null
        }
        _state.update { it.copy(pendingConflict = null) }
        conflictToCancel?.cancel()
        jobToCancel?.cancel()
    }

    fun cancelAll() {
        val jobToCancel: Job?
        val conflictToCancel: CompletableDeferred<ConflictDecision>?
        synchronized(lock) {
            queue.clear()
            val hasActive = workerJob != null || activeExecutionJob != null || _state.value.activeJob != null
            if (hasActive) {
                activeCancelRequested = true
            }
            jobToCancel = activeExecutionJob
            conflictToCancel = pendingConflictDeferred
            pendingConflictDeferred = null
            _state.update { current ->
                current.copy(
                    queuedCount = 0,
                    pendingConflict = null
                )
            }
        }
        conflictToCancel?.cancel()
        jobToCancel?.cancel()
    }

    fun resolveConflict(decision: ConflictDecision) {
        val deferred = synchronized(lock) {
            val current = pendingConflictDeferred
            pendingConflictDeferred = null
            current
        }
        _state.update { it.copy(pendingConflict = null) }
        deferred?.complete(decision)
    }

    fun setProgressDialogVisible(visible: Boolean) {
        _state.update { it.copy(isProgressDialogVisible = visible) }
    }

    private suspend fun processQueue() {
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val nextRequest: TransferRequest = synchronized(lock) {
                    val polled = queue.removeFirstOrNull()
                    if (polled == null) {
                        workerJob = null
                        activeExecutionJob = null
                        activeCancelRequested = false
                        _state.update {
                            it.copy(
                                activeJob = null,
                                queuedCount = 0,
                                pendingConflict = null,
                                isProgressDialogVisible = false
                            )
                        }
                        return
                    }
                    activeCancelRequested = false
                    _state.update { current ->
                        current.copy(
                            activeJob = TransferJobUi(
                                jobId = polled.jobId,
                                operation = polled.operation,
                                progress = null
                            ),
                            queuedCount = queue.size,
                            pendingConflict = null,
                            isProgressDialogVisible = true
                        )
                    }
                    polled
                }

                runSingleJob(nextRequest)
            }
        } finally {
            synchronized(lock) {
                if (workerJob?.isActive != true) {
                    workerJob = null
                }
                activeExecutionJob = null
                pendingConflictDeferred?.cancel()
                pendingConflictDeferred = null
            }
        }
    }

    private suspend fun runSingleJob(request: TransferRequest) {
        val engine = TransferEngine(
            sourceProvider = vfsProvider,
            destProvider = vfsProvider,
            bufferSize = engineBufferSize,
            ioDispatcher = ioDispatcher
        )

        var outcome: TransferOutcome? = null

        val executionJob = scope.launch {
            try {
                val result = engine.execute(
                    request = request,
                    onProgress = { progress ->
                        _state.update { current ->
                            val active = current.activeJob
                            if (active != null && active.jobId == request.jobId) {
                                current.copy(activeJob = active.copy(progress = progress))
                            } else {
                                current
                            }
                        }
                    },
                    onConflict = { info ->
                        awaitConflictDecision(info)
                    }
                )
                outcome = TransferOutcome.Completed(
                    jobId = request.jobId,
                    operation = request.operation,
                    result = result
                )
            } catch (ce: CancellationException) {
                val userCancelled = synchronized(lock) { activeCancelRequested }
                if (userCancelled && scope.isActive) {
                    outcome = TransferOutcome.Cancelled(request.jobId)
                } else {
                    throw ce
                }
            } catch (e: Exception) {
                val message = e.message?.takeIf { it.isNotBlank() }
                    ?: "Transfer gagal karena kesalahan tidak diketahui"
                outcome = TransferOutcome.Failed(
                    jobId = request.jobId,
                    message = message
                )
            }
        }

        val cancelImmediately = synchronized(lock) {
            activeExecutionJob = executionJob
            activeCancelRequested
        }
        if (cancelImmediately) {
            executionJob.cancel()
        }

        try {
            executionJob.join()
        } finally {
            synchronized(lock) {
                if (activeExecutionJob === executionJob) {
                    activeExecutionJob = null
                }
                pendingConflictDeferred?.cancel()
                pendingConflictDeferred = null
            }
            _state.update { it.copy(pendingConflict = null) }
        }

        currentCoroutineContext().ensureActive()

        val finalOutcome = outcome ?: if (synchronized(lock) { activeCancelRequested }) {
            TransferOutcome.Cancelled(request.jobId)
        } else {
            null
        }

        if (finalOutcome != null) {
            _outcomes.emit(finalOutcome)
        }
    }

    private suspend fun awaitConflictDecision(info: ConflictInfo): ConflictDecision {
        val deferred = CompletableDeferred<ConflictDecision>()
        synchronized(lock) {
            if (activeCancelRequested) {
                throw CancellationException("Transfer dibatalkan oleh pengguna")
            }
            pendingConflictDeferred = deferred
        }
        _state.update { it.copy(pendingConflict = info) }
        return try {
            deferred.await()
        } finally {
            synchronized(lock) {
                if (pendingConflictDeferred === deferred) {
                    pendingConflictDeferred = null
                }
            }
            _state.update { current ->
                if (current.pendingConflict == info) {
                    current.copy(pendingConflict = null)
                } else {
                    current
                }
            }
        }
    }

    companion object {
        @Volatile
        private var instance: TransferQueueManager? = null

        fun getInstance(): TransferQueueManager {
            return instance ?: synchronized(this) {
                instance ?: TransferQueueManager(
                    vfsProvider = LocalVfsProvider(),
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                ).also { instance = it }
            }
        }
    }
}
