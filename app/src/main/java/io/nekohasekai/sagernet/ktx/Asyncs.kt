@file:Suppress("EXPERIMENTAL_API_USAGE")

package io.nekohasekai.sagernet.ktx

import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import java.util.concurrent.Executors

fun runOnDefaultDispatcher(block: suspend CoroutineScope.() -> Unit) =
    GlobalScope.launch(Dispatchers.Default, block = block)

// single thread for callbacks that must run in order and may block
// (DefaultNetworkListener -> Libcore.resetAllConnections)
private val serialDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

fun runOnSerialDispatcher(block: suspend CoroutineScope.() -> Unit) =
    GlobalScope.launch(serialDispatcher, block = block)

fun Fragment.runOnLifecycleDispatcher(block: suspend CoroutineScope.() -> Unit) =
    lifecycleScope.launch(Dispatchers.Default, block = block)

fun runOnIoDispatcher(block: suspend CoroutineScope.() -> Unit) =
    GlobalScope.launch(Dispatchers.IO, block = block)

suspend fun <T> onIoDispatcher(block: suspend CoroutineScope.() -> T) =
    withContext(Dispatchers.IO, block = block)

fun runOnMainDispatcher(block: suspend CoroutineScope.() -> Unit) =
    GlobalScope.launch(Dispatchers.Main.immediate, block = block)

suspend fun <T> onMainDispatcher(block: suspend CoroutineScope.() -> T) =
    withContext(Dispatchers.Main.immediate, block = block)
