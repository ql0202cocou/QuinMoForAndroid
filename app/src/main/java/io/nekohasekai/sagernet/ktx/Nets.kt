@file:Suppress("SpellCheckingInspection")

package io.nekohasekai.sagernet.ktx

import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.fmt.AbstractBean
import libcore.Libcore
import moe.matsuri.nb4a.utils.NGUtil
import okhttp3.HttpUrl
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

fun linkBuilder() = HttpUrl.Builder().scheme("https")

fun HttpUrl.Builder.toLink(scheme: String, appendDefaultPort: Boolean = true): String {
    var url = build()
    val defaultPort = HttpUrl.defaultPort(url.scheme)
    var replace = false
    if (appendDefaultPort && url.port == defaultPort) {
        url = url.newBuilder().port(14514).build()
        replace = true
    }
    return url.toString().replace("${url.scheme}://", "$scheme://").let {
        if (replace) it.replace(":14514", ":$defaultPort") else it
    }
}

fun String.isIpAddress(): Boolean {
    return NGUtil.isIpv4Address(this) || NGUtil.isIpv6Address(this)
}

// Resolve via the group's proxyServerNameserver (first usable address, e.g. DoH);
// returns null on failure so callers can fall back to system DNS.
fun lookupViaNameserver(nameserver: String?, domain: String): List<InetAddress>? {
    val server = nameserver
        ?.lineSequence()?.map { it.trim() }
        ?.firstOrNull { it.isNotBlank() && !it.startsWith("#") && it != "local" }
        ?: return null
    return try {
        Libcore.lookupHost(server, domain).lineSequence()
            .mapNotNull { ip -> runCatching { InetAddress.getByName(ip.trim()) }.getOrNull() }
            .toList().takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        Logs.d("Lookup $domain via $server failed: ${e.readableMessage}")
        null
    }
}

fun String.isIpAddressV6(): Boolean {
    return NGUtil.isIpv6Address(this)
}

// [2001:4860:4860::8888] -> 2001:4860:4860::8888
fun String.unwrapIPV6Host(): String {
    if (startsWith("[") && endsWith("]")) {
        return substring(1, length - 1).unwrapIPV6Host()
    }
    return this
}

// [2001:4860:4860::8888] or 2001:4860:4860::8888 -> [2001:4860:4860::8888]
fun String.wrapIPV6Host(): String {
    val unwrapped = this.unwrapIPV6Host()
    if (unwrapped.isIpAddressV6()) {
        return "[$unwrapped]"
    } else {
        return this
    }
}

fun AbstractBean.wrapUri(): String {
    return "${finalAddress.wrapIPV6Host()}:$finalPort"
}

// Ports handed out but not yet bound by their consumer (external cores bind only
// after process spawn). Keep them out of rotation so concurrent callers — e.g.
// parallel URL-test instances — can't be assigned the same ephemeral port.
private val mkPortAllocated = ConcurrentHashMap.newKeySet<Int>()

fun mkPort(): Int {
    while (true) {
        val socket = Socket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(0))
        val port = socket.localPort
        socket.close()
        if (mkPortAllocated.add(port)) return port
    }
}

const val USER_AGENT = "NekoBox/Android/" + BuildConfig.VERSION_NAME + " (Prefer ClashMeta Format)"
