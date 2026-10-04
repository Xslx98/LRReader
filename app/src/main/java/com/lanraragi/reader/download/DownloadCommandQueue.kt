package com.lanraragi.reader.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Serial command queue behind [DownloadService.onStartCommand] (audit
 * 2026-10-04 C08).
 *
 * One consumer waits for [awaitReady] once, then runs every command strictly
 * in arrival order on [handlerDispatcher]. Two rules the service used to get
 * wrong:
 *  - A command that throws is reported through [onError] and the loop goes
 *    on. Before, anything but a `NullPointerException` ended the loop for
 *    good: later commands piled up unread and the foreground notification,
 *    wake lock and Wi-Fi lock were never released.
 *  - [afterEach] receives the startId of the command just handled, not of
 *    the latest one delivered. The service passes it to `stopSelfResult`,
 *    which refuses to stop while a newer start is still queued — a plain
 *    `stopSelf()` after an idle-making command (delete, stop) destroyed the
 *    service with the next command still waiting.
 */
internal class DownloadCommandQueue<T>(
    private val scope: CoroutineScope,
    private val handlerDispatcher: CoroutineDispatcher,
    private val awaitReady: suspend () -> Unit,
    private val handle: (T) -> Unit,
    private val onError: (T, Exception) -> Unit,
    private val afterEach: (startId: Int) -> Unit,
) {
    private class Command<T>(val value: T, val startId: Int)

    private val commands = Channel<Command<T>>(Channel.UNLIMITED)
    private var consumer: Job? = null

    /** Queues [value]; starts the consumer on first use. */
    fun submit(value: T, startId: Int) {
        if (consumer == null) consumer = scope.launch { consume() }
        commands.trySend(Command(value, startId))
    }

    private suspend fun consume() {
        awaitReady()
        for (command in commands) {
            withContext(handlerDispatcher) {
                try {
                    handle(command.value)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onError(command.value, e)
                }
                afterEach(command.startId)
            }
        }
    }
}
