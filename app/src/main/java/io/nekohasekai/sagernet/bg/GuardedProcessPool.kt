package io.nekohasekai.sagernet.bg

import android.os.Build
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.annotation.MainThread
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.utils.Commandline
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import libcore.Libcore
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class GuardedProcessPool(private val onFatal: suspend (IOException) -> Unit) : CoroutineScope {
    companion object {
        private val pid by lazy {
            Class.forName("java.lang.ProcessManager\$ProcessImpl").getDeclaredField("pid")
                .apply { isAccessible = true }
        }
    }

    private inner class Guard(
        private val cmd: List<String>,
        private val env: Map<String, String> = mapOf()
    ) {
        private lateinit var process: Process

        // set on the looper coroutine's first dispatch; close() destroys only
        // guards whose looper never started, started loopers clean up in their
        // own finally (with the SIGTERM grace period on API < 24)
        @Volatile
        var looperStarted = false

        // the current process has a thread blocked in waitFor (spawned at the
        // top of the looper loop); cleared on restart until the next iteration
        @Volatile
        private var hasWaiter = false

        private fun streamLogger(input: InputStream, logger: (String) -> Unit) = try {
            input.bufferedReader().forEachLine(logger)
        } catch (_: IOException) {
        }    // ignore

        fun start() {
            process = ProcessBuilder(cmd).directory(SagerNet.application.noBackupFilesDir).apply {
                environment().putAll(env)
            }.start()
        }

        fun destroy() = process.destroy()

        @DelicateCoroutinesApi
        suspend fun looper(onRestartCallback: (suspend () -> Unit)?) {
            looperStarted = true
            var running = true
            val cmdName = File(cmd.first()).nameWithoutExtension
            val exitChannel = Channel<Int>(1) // buffered: cleanup may give up receiving, and a blocked send would leak the daemon thread
            try {
                while (true) {
                    thread(name = "stderr-$cmdName") {
                        streamLogger(process.errorStream) { Libcore.nekoLogPrintln("[$cmdName] $it") }
                    }
                    thread(name = "stdout-$cmdName") {
                        streamLogger(process.inputStream) { Libcore.nekoLogPrintln("[$cmdName] $it") }
                        // this thread also acts as a daemon thread for waitFor
                        runBlocking { exitChannel.send(process.waitFor()) }
                    }
                    hasWaiter = true
                    val startTime = SystemClock.elapsedRealtime()
                    val exitCode = exitChannel.receive()
                    running = false
                    when {
                        SystemClock.elapsedRealtime() - startTime < 1000 -> throw IOException(
                            "$cmdName exits too fast (exit code: $exitCode)"
                        )

                        exitCode == 128 + OsConstants.SIGKILL -> Logs.w("$cmdName was killed")
                        else -> Logs.w(IOException("$cmdName unexpectedly exits with code $exitCode"))
                    }
                    Logs.i("restart process: ${Commandline.toString(cmd)} (last exit code: $exitCode)")
                    // clear before start(): a cancellation landing after this
                    // point must see that the new process has no waiter yet
                    hasWaiter = false
                    start()
                    running = true
                    onRestartCallback?.invoke()
                }
            } catch (e: IOException) {
                Logs.w("error occurred. stop guard: ${Commandline.toString(cmd)}")
                GlobalScope.launch(Dispatchers.Main) { onFatal(e) }
            } finally {
                if (running) withContext(NonCancellable) {  // clean-up cannot be cancelled
                    if (!hasWaiter) {
                        // Cancellation landed between the restart above and the
                        // next iteration's waiter threads: nothing is in waitFor
                        // for the new process, so exitChannel would never fire
                        // (every timeout below wasted) and the process would
                        // zombie until GC. trySend: a racing waiter may already
                        // hold the channel's single buffer slot.
                        thread(name = "waitfor-$cmdName") {
                            exitChannel.trySend(process.waitFor())
                        }
                    }
                    if (Build.VERSION.SDK_INT < 24) {
                        try {
                            Os.kill(pid.get(process) as Int, OsConstants.SIGTERM)
                        } catch (e: ErrnoException) {
                            if (e.errno != OsConstants.ESRCH) Logs.w(e)
                        } catch (e: ReflectiveOperationException) {
                            Logs.w(e)
                        }
                        if (withTimeoutOrNull(500) { exitChannel.receive() } != null) return@withContext
                    }
                    process.destroy()                       // kill the process
                    if (Build.VERSION.SDK_INT >= 26) {
                        if (withTimeoutOrNull(1000) { exitChannel.receive() } != null) return@withContext
                        process.destroyForcibly()           // Force to kill the process if it's still alive
                    }
                    // don't hang forever if the process ignores destroy()
                    withTimeoutOrNull(5000) { exitChannel.receive() }
                }                                           // otherwise process already exited, nothing to be done
            }
        }
    }

    override val coroutineContext = Dispatchers.Main.immediate + Job()
    val processCount = AtomicInteger(0)

    // every successfully started guard, so close() can reap processes whose
    // looper coroutine never ran (pool cancelled after the isActive check)
    private val guards = CopyOnWriteArrayList<Guard>()

    fun start(
        cmd: List<String>,
        env: MutableMap<String, String> = mutableMapOf(),
        onRestartCallback: (suspend () -> Unit)? = null
    ) {
        Logs.i("start process: ${Commandline.toString(cmd)}")
        Guard(cmd, env).apply {
            start() // if start fails, IOException will be thrown directly
            guards.add(this)
            if (!coroutineContext[Job]!!.isActive) {
                // close() already cancelled this pool: the looper launch below
                // would never run and nothing else would kill this process
                destroy()
                return
            }
            launch { looper(onRestartCallback) }
        }
        processCount.incrementAndGet()
    }

    @MainThread
    fun close(scope: CoroutineScope): Job {
        cancel()
        // reap processes whose looper coroutine was cancelled before its
        // first dispatch; guards with a started looper clean up in their own
        // finally, so an unconditional destroy here would cut the graceful
        // shutdown short
        guards.forEach { if (!it.looperStarted) it.destroy() }
        // The returned Job completes once every guard looper — including its
        // process-killing finally — has exited; callers that must outlive the
        // guards hook onto it instead of joining (joining from the main
        // thread would deadlock the loopers' Main-dispatched cleanup).
        return scope.launch { this@GuardedProcessPool.coroutineContext[Job]!!.cancelAndJoin() }
    }
}
