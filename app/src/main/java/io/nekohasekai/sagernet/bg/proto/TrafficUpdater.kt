package io.nekohasekai.sagernet.bg.proto

import android.os.SystemClock

class TrafficUpdater(
    private val box: libcore.BoxInstance,
    val items: List<TrafficLooperData>, // contain "bypass"
) {

    class TrafficLooperData(
        // Don't associate proxyEntity
        // @Volatile: read/written by TrafficLooper, TrafficUpdater and binder
        // threads without synchronization; plain Longs could tear
        @Volatile var tag: String,
        @Volatile var tx: Long = 0,
        @Volatile var rx: Long = 0,
        @Volatile var txBase: Long = 0,
        @Volatile var rxBase: Long = 0,
        @Volatile var txRate: Long = 0,
        @Volatile var rxRate: Long = 0,
        @Volatile var lastUpdate: Long = 0,
        @Volatile var ignore: Boolean = false,
    )

    private fun updateOne(item: TrafficLooperData): TrafficLooperData {
        // last update (monotonic clock: wall-clock jumps would skew the rates)
        val now = SystemClock.elapsedRealtime()
        val interval = now - item.lastUpdate
        item.lastUpdate = now
        if (interval <= 0) return item.apply {
            rxRate = 0
            txRate = 0
        }

        // query
        val tx = box.queryStats(item.tag, "uplink")
        val rx = box.queryStats(item.tag, "downlink")

        // add diff
        item.rx += rx
        item.tx += tx
        item.rxRate = rx * 1000 / interval
        item.txRate = tx * 1000 / interval

        // return diff
        return TrafficLooperData(
            tag = item.tag,
            rx = rx,
            tx = tx,
            rxRate = item.rxRate,
            txRate = item.txRate,
        )
    }

    // not suspend: called inside TrafficLooper's synchronized block, which
    // forbids suspension points (none are needed here)
    fun updateAll() {
        val updated = mutableMapOf<String, TrafficLooperData>() // diffs
        items.forEach { item ->
            if (item.ignore) return@forEach
            var diff = updated[item.tag]
            // query a tag only once
            if (diff == null) {
                diff = updateOne(item)
                updated[item.tag] = diff
            } else {
                item.rx += diff.rx
                item.tx += diff.tx
                item.rxRate = diff.rxRate
                item.txRate = diff.txRate
            }
        }
//        Logs.d(JavaUtil.gson.toJson(items))
//        Logs.d(JavaUtil.gson.toJson(updated))
    }
}
