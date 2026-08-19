package moe.matsuri.nb4a.proxy.anytls

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.isIpAddress
import moe.matsuri.nb4a.utils.listByLineOrComma
import org.yaml.snakeyaml.Yaml
import java.security.MessageDigest
import java.security.cert.CertificateFactory

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
    // Certificate pinning wins over allowInsecure: mihomo's `fingerprint` is
    // implemented as InsecureSkipVerify + VerifyConnection, and a leaf hash match
    // skips name/expiry checks — which already covers the usual self-signed cases
    // that make users reach for allowInsecure.
    val certPin = bean.certificateFingerprint.ifBlank {
        bean.certificates.takeIf { it.isNotBlank() }?.let { certificateSha256(it) } ?: ""
    }
    if (certPin.isNotBlank()) {
        // mihomo has no custom-CA option ("certificate" is the mTLS client cert),
        // pin the server certificate's SHA-256 instead. Works for the usual
        // self-signed case; a CA cert only matches if the server sends it in-chain.
        proxy["fingerprint"] = certPin
    } else if (bean.allowInsecure || DataStore.globalAllowInsecure) {
        proxy["skip-cert-verify"] = true
    }
    if (bean.utlsFingerprint.isNotBlank()) proxy["client-fingerprint"] = bean.utlsFingerprint
    // mihomo outbound.ECHOptions{enable, config}; sing-box gets the same value via tls.ech.config
    if (bean.echConfig.isNotBlank()) {
        proxy["ech-opts"] = linkedMapOf<String, Any?>(
            "enable" to true,
            "config" to bean.echConfig,
        )
    }

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

// SHA-256 (lowercase hex) of the first certificate in the PEM, matching
// mihomo's `fingerprint` pinning format.
private fun certificateSha256(pem: String): String? = runCatching {
    val der = CertificateFactory.getInstance("X.509")
        .generateCertificate(pem.byteInputStream()).encoded
    MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { "%02x".format(it) }
}.getOrNull()
