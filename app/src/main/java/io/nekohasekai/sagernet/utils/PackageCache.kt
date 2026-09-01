package io.nekohasekai.sagernet.utils

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.listenForPackageChanges
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.matsuri.nb4a.plugin.Plugins
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

object PackageCache {

    lateinit var installedPackages: Map<String, PackageInfo>
    lateinit var installedPluginPackages: Map<String, PackageInfo>
    lateinit var installedApps: Map<String, ApplicationInfo>
    lateinit var packageMap: Map<String, Int>
    lateinit var uidMap: Map<Int, Set<String>>
    val loaded = Mutex(true)
    var registerd = AtomicBoolean(false)

    // called from init (suspend)
    fun register() {
        if (registerd.getAndSet(true)) return
        reload()
        app.listenForPackageChanges(false) {
            reload()
            labelMap.clear()
        }
        loaded.unlock()
    }

    @Synchronized // register() and the package-change receiver may race
    @SuppressLint("InlinedApi")
    fun reload() {
        val rawPackageInfo = app.packageManager.getInstalledPackages(
            PackageManager.MATCH_UNINSTALLED_PACKAGES
                    or PackageManager.GET_PERMISSIONS
                    or PackageManager.GET_PROVIDERS
                    or PackageManager.GET_META_DATA
        )

        installedPackages = rawPackageInfo.filter {
            when (it.packageName) {
                "android" -> true
                else -> it.requestedPermissions?.contains(Manifest.permission.INTERNET) == true
            }
        }.associateBy { it.packageName }

        installedPluginPackages = rawPackageInfo.filter {
            Plugins.isExe(it)
        }.associateBy { it.packageName }

        val installed = app.packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        installedApps = installed.associateBy { it.packageName }
        // swap the whole map like the fields above: packageNameByUid reads
        // this from a gomobile callback thread with no synchronization, so
        // mutating a shared HashMap in place can corrupt a concurrent read.
        // uidMap is assigned before packageMap: awaitLoadSync early-returns on
        // ::packageMap.isInitialized, so packageMap must be the last write.
        val newUidMap = HashMap<Int, HashSet<String>>()
        for (info in installed) {
            val uid = info.uid
            newUidMap.getOrPut(uid) { HashSet() }.add(info.packageName)
        }
        uidMap = newUidMap
        packageMap = installed.associate { it.packageName to it.uid }
    }

    operator fun get(uid: Int) = uidMap[uid]
    operator fun get(packageName: String) = packageMap[packageName]

    fun awaitLoadSync() {
        if (::packageMap.isInitialized && ::uidMap.isInitialized) {
            return
        }
        if (!registerd.get()) {
            register()
            return
        }
        runBlocking {
            loaded.withLock {
                // just await
            }
        }
    }

    // written from background threads, cleared from the package-change receiver
    private val labelMap = ConcurrentHashMap<String, String>()
    fun loadLabel(packageName: String): String {
        var label = labelMap[packageName]
        if (label != null) return label
        val info = installedApps[packageName] ?: return packageName
        label = info.loadLabel(app.packageManager).toString()
        labelMap[packageName] = label
        return label
    }

}