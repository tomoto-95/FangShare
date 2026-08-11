package com.fangshare.app.service

import com.fangshare.app.model.TransferTask
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

object ReceiveNotifier {

    private val _receivedFiles = MutableSharedFlow<TransferTask>(replay = 10, extraBufferCapacity = 10)
    val receivedFiles = _receivedFiles.asSharedFlow()

    private val _pendingFiles = MutableStateFlow<List<TransferTask>>(emptyList())
    val pendingFiles = _pendingFiles.asStateFlow()

    fun notifyFileReceived(task: TransferTask) {
        _receivedFiles.tryEmit(task)
        _pendingFiles.value = _pendingFiles.value + task
    }

    fun clearPending() {
        _pendingFiles.value = emptyList()
    }
}
