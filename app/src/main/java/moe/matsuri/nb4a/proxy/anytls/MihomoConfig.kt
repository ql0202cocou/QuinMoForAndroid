package moe.matsuri.nb4a.proxy.anytls

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.isIpAddress
import moe.matsuri.nb4a.utils.listByLineOrComma
import org.yaml.snakeyaml.Yaml

// Builds a mihomo client config for an AnyTLS profile:
// a local socks listener chained from sing-box, and the profile as proxy.
fun buildMihomoConfig(bean: AnyTLSBean, port: Int): String {
    val proxy = LinkedHashMap<String, Any?>()
    proxy["name"] = "anytls-out"
    proxy["type"] = "anytls"
    proxy["server"] = bean.finalAddress
    proxy["port"] = bean.finalPort
    proxy["password"] = bean.password
    proxy["udp"] = true
    // 经 mapping 外核只能拨到本地地址，TLS SNI 需要显式兜底
    val sni = bean.sni.takeIf { it.isNotBlank() }
        ?: bean.serverAddress.takeIf { it.isNotBlank() && !it.isIpAddress() }
    if (sni != null) proxy["sni"] = sni
    if (bean.alpn.isNotBlank()) proxy["alpn"] = bean.alpn.listByLineOrComma()
    if (bean.allowInsecure || DataStore.globalAllowInsecure) proxy["skip-cert-verify"] = true
    if (bean.utlsFingerprint.isNotBlank()) proxy["client-fingerprint"] = bean.utlsFingerprint

    val listener = LinkedHashMap<String, Any?>()
    listener["name"] = "socks-in"
    listener["type"] = "socks"
    listener["listen"] = "127.0.0.1"
    listener["port"] = port
    listener["udp"] = true

    val config = LinkedHashMap<String, Any?>()
    config["log-level"] = if (DataStore.logLevel > 0) "debug" else "warning"
    config["mode"] = "rule"
    config["listeners"] = listOf(listener)
    config["proxies"] = listOf(proxy)
    config["rules"] = listOf("MATCH,anytls-out")

    return Yaml().dump(config)
}
