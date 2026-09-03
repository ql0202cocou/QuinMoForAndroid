package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.bg.SubscriptionUpdater
import io.nekohasekai.sagernet.ktx.applyDefaultValues

object GroupManager {

    interface Listener {
        suspend fun groupAdd(group: ProxyGroup)
        suspend fun groupUpdated(group: ProxyGroup)

        suspend fun groupRemoved(groupId: Long)
        suspend fun groupUpdated(groupId: Long)
    }

    interface Interface {
        suspend fun confirm(message: String): Boolean
        suspend fun alert(message: String)
        suspend fun onUpdateSuccess(
            group: ProxyGroup,
            changed: Int,
            added: List<String>,
            updated: Map<String, String>,
            deleted: List<String>,
            duplicate: List<String>,
            byUser: Boolean
        )

        suspend fun onUpdateFailure(group: ProxyGroup, message: String)
    }

    private val listeners = ArrayList<Listener>()
    var userInterface: Interface? = null

    suspend fun iterator(what: suspend Listener.() -> Unit) {
        synchronized(listeners) {
            listeners.toList()
        }.forEach { listener ->
            what(listener)
        }
    }

    fun addListener(listener: Listener) {
        synchronized(listeners) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: Listener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    suspend fun clearGroup(groupId: Long) {
        val selected = DataStore.selectedProxy
        if (selected > 0L && SagerDatabase.proxyDao.getById(selected)?.groupId == groupId) {
            DataStore.selectedProxy = 0L
        }
        SagerDatabase.proxyDao.deleteAll(groupId)
        resetDanglingGroupProxies()
        iterator { groupUpdated(groupId) }
    }

    fun rearrange(groupId: Long) {
        val entities = SagerDatabase.proxyDao.getByGroup(groupId)
        for (index in entities.indices) {
            entities[index].userOrder = (index + 1).toLong()
        }
        SagerDatabase.proxyDao.updateProxy(entities)
    }

    suspend fun postUpdate(group: ProxyGroup) {
        iterator { groupUpdated(group) }
    }

    suspend fun postUpdate(groupId: Long) {
        postUpdate(SagerDatabase.groupDao.getById(groupId) ?: return)
    }

    suspend fun postReload(groupId: Long) {
        iterator { groupUpdated(groupId) }
    }

    suspend fun createGroup(group: ProxyGroup): ProxyGroup {
        group.userOrder = SagerDatabase.groupDao.nextOrder() ?: 1
        group.id = SagerDatabase.groupDao.createGroup(group.applyDefaultValues())
        iterator { groupAdd(group) }
        if (group.type == GroupType.SUBSCRIPTION) {
            SubscriptionUpdater.reconfigureUpdater()
        }
        return group
    }

    suspend fun updateGroup(group: ProxyGroup) {
        SagerDatabase.groupDao.updateGroup(group)
        iterator { groupUpdated(group) }
        // 分组类型可能在订阅与基本之间切换：不再订阅的分组要取消周期任务，
        // 新订阅的分组要排上；reconfigureUpdater 内部先 cancel 再按现状重排
        SubscriptionUpdater.reconfigureUpdater()
    }

    suspend fun deleteGroup(groupId: Long) {
        val selected = DataStore.selectedProxy
        if (selected > 0L && SagerDatabase.proxyDao.getById(selected)?.groupId == groupId) {
            DataStore.selectedProxy = 0L
        }
        SagerDatabase.instance.runInTransaction {
            SagerDatabase.groupDao.deleteById(groupId)
            SagerDatabase.proxyDao.deleteByGroup(groupId)
            resetDanglingGroupProxies()
        }
        if (DataStore.selectedGroup == groupId) resetSelectedGroup()
        iterator { groupRemoved(groupId) }
        SubscriptionUpdater.reconfigureUpdater()
    }

    suspend fun deleteGroup(group: List<ProxyGroup>) {
        val selected = DataStore.selectedProxy
        val selectedGroupId = if (selected > 0L) {
            SagerDatabase.proxyDao.getById(selected)?.groupId
        } else null
        if (selectedGroupId != null && group.any { it.id == selectedGroupId }) {
            DataStore.selectedProxy = 0L
        }
        SagerDatabase.instance.runInTransaction {
            SagerDatabase.groupDao.deleteGroup(group)
            SagerDatabase.proxyDao.deleteByGroup(group.map { it.id }.toLongArray())
            resetDanglingGroupProxies()
        }
        if (group.any { it.id == DataStore.selectedGroup }) resetSelectedGroup()
        for (proxyGroup in group) iterator { groupRemoved(proxyGroup.id) }
        SubscriptionUpdater.reconfigureUpdater()
    }

    // Profiles deleted with their group may still be referenced as another
    // group's frontProxy/landingProxy; ConfigBuilder.resolveChain would get
    // null from getById and silently drop the user's front/landing proxy.
    fun resetDanglingGroupProxies() {
        SagerDatabase.groupDao.allGroups().forEach { group ->
            var changed = false
            if (group.frontProxy > 0L && SagerDatabase.proxyDao.getById(group.frontProxy) == null) {
                group.frontProxy = -1L
                changed = true
            }
            if (group.landingProxy > 0L && SagerDatabase.proxyDao.getById(group.landingProxy) == null) {
                group.landingProxy = -1L
                changed = true
            }
            if (changed) SagerDatabase.groupDao.updateGroup(group)
        }
    }

    // Mirrors the fallback in DataStore.currentGroup(): fall back to the first
    // remaining group, or -1 so currentGroup() recreates the ungrouped group.
    private fun resetSelectedGroup() {
        DataStore.selectedGroup = SagerDatabase.groupDao.allGroups().firstOrNull()?.id ?: -1L
    }

}