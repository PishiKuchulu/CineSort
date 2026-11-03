package com.cinesort.app.orchestration

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One process-wide lock for Undo (audit B.8). The three entry points
 * (Home, History, Report) each build their own [com.cinesort.core.domain.execute.UndoExecutor]
 * and used to guard only against themselves, so the same run could be
 * undone twice concurrently from two screens. The executor tolerates that
 * (UNDONE operations are skipped), but two undos walking the same
 * operations can still race on one file; serializing here removes the
 * race instead of relying on that tolerance.
 */
@Singleton
class UndoCoordinator @Inject constructor() {

    private val mutex = Mutex()

    suspend fun <T> withUndoLock(block: suspend () -> T): T = mutex.withLock { block() }
}
