package io.nekohasekai.sagernet.fmt.v2ray

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.isIpAddress
import io.nekohasekai.sagernet.ktx.toStringPretty
import moe.matsuri.nb4a.utils.listByLineOrComma
import org.json.JSONArray
import org.json.JSONObject

// Builds an Xray-core client config for a VMess/VLESS profile:
// a local socks inbound chained from sing-box, and the profile as outbound.
fun buildXrayConfig(bean: VMessBean, port: Int): String {
    val user = JSONObject().apply {
        put("id", bean.uuid)
        if (bean.isVLESS) {
            put("encryption", "none")
            if (bean.encryption.isNotBlank() && bean.encryption != "auto") {
                put("flow", bean.encryption)
            }
        } else {
            put("alterId", bean.alterId)
            put("security", bean.encryption.takeIf { it.isNotBlank() } ?: "auto")
        }
    }

    val outbound = JSONObject().apply {
        put("protocol", if (bean.isVLESS) "vless" else "vmess")
        put("settings", JSONObject().apply {
            put("vnext", JSONArray().apply {
                put(JSONObject().apply {
                    put("address", bean.finalAddress)
                    put("port", bean.finalPort)
                    put("users", JSONArray().apply { put(user) })
                })
            })
        })
        put("streamSettings", buildXrayStreamSettings(bean))
        // xudp rides on xray mux; packetaddr is not supported by xray.
        // vision flow doesn't support mux; without mux VLESS carries UDP natively.
        if (!bean.isVisionFlow && (bean.enableMux || bean.packetEncoding == 2)) {
            put("mux", JSONObject().apply {
                put("enabled", true)
                put("concurrency", if (bean.muxConcurrency > 0) bean.muxConcurrency else 8)
                if (bean.packetEncoding == 2) {
                    put("xudpConcurrency", 16)
                    put("xudpProxyUDP443", "reject")
                }
            })
        }
    }

    return JSONObject().apply {
        put("log", JSONObject().apply {
            put("loglevel", if (DataStore.logLevel > 0) "debug" else "warning")
        })
        put("inbounds", JSONArray().apply {
            put(JSONObject().apply {
                put("listen", "127.0.0.1")
                put("port", port)
                put("protocol", "socks")
                put("settings", JSONObject().apply { put("udp", true) })
            })
        })
        put("outbounds", JSONArray().apply { put(outbound) })
    }.toStringPretty()
}

private fun buildXrayStreamSettings(bean: VMessBean): JSONObject {
    // 经 mapping 外核只能拨到本地地址，TLS SNI 需要显式兜底
    val sni = bean.sni.takeIf { it.isNotBlank() }
        ?: bean.serverAddress.takeIf { it.isNotBlank() && !it.isIpAddress() }
    return JSONObject().apply {
        // transport
        when (bean.type) {
            "ws" -> {
                put("network", "ws")
                put("wsSettings", JSONObject().apply {
                    val ed = bean.resolveWsEarlyData()
                    put("path", ed.path)
                    ed.maxEarlyData?.let { put("maxEarlyData", it) }
                    ed.headerName?.let { put("earlyDataHeaderName", it) }
                    if (bean.host.isNotBlank()) {
                        put("headers", JSONObject().apply { put("Host", bean.host) })
                    }
                })
            }

            "http" -> if (bean.isTLS()) {
                put("network", "http")
                put("httpSettings", JSONObject().apply {
                    if (bean.host.isNotBlank()) {
                        put("host", JSONArray(bean.host.split(",")))
                    }
                    put("path", bean.path.takeIf { it.isNotBlank() } ?: "/")
                })
            } else {
                // v2ray style tcp fake http
                put("network", "tcp")
                put("tcpSettings", JSONObject().apply {
                    put("header", JSONObject().apply {
                        put("type", "http")
                        put("request", JSONObject().apply {
                            put("path", JSONArray().apply {
                                put(bean.path.takeIf { it.isNotBlank() } ?: "/")
                            })
                            if (bean.host.isNotBlank()) {
                                put("headers", JSONObject().apply {
                                    put("Host", JSONArray(bean.host.split(",")))
                                })
                            }
                        })
                    })
                })
            }

            "quic" -> {
                put("network", "quic")
                put("quicSettings", JSONObject().apply {
                    put("header", JSONObject().apply { put("type", "none") })
                })
            }

            "grpc" -> {
                put("network", "grpc")
                put("grpcSettings", JSONObject().apply {
                    put("serviceName", bean.path)
                })
            }

            "httpupgrade" -> {
                put("network", "httpupgrade")
                put("httpUpgradeSettings", JSONObject().apply {
                    if (bean.host.isNotBlank()) put("host", bean.host)
                    put("path", bean.path.takeIf { it.isNotBlank() } ?: "/")
                })
            }

            else -> put("network", "tcp")
        }

        // security
        val fp = bean.effectiveUtlsFingerprint()
        if (bean.realityPubKey.isNotBlank()) {
            put("security", "reality")
            put("realitySettings", JSONObject().apply {
                if (sni != null) put("serverName", sni)
                put("publicKey", bean.realityPubKey)
                if (bean.realityShortId.isNotBlank()) put("shortId", bean.realityShortId)
                // post-quantum REALITY; Xray-only, sing-box 1.13 does not support it
                if (bean.realityMldsa65Verify.isNotBlank()) {
                    put("mldsa65Verify", bean.realityMldsa65Verify)
                }
                fp?.let { put("fingerprint", it) }
            })
        } else if (bean.security == "tls") {
            put("security", "tls")
            put("tlsSettings", JSONObject().apply {
                if (sni != null) put("serverName", sni)
                if (bean.alpn.isNotBlank()) {
                    put("alpn", JSONArray(bean.alpn.listByLineOrComma()))
                }
                if (bean.allowInsecure || DataStore.globalAllowInsecure) {
                    put("allowInsecure", true)
                }
                fp?.let { put("fingerprint", it) }
                if (bean.certificates.isNotBlank()) {
                    put("certificates", JSONArray().apply {
                        put(JSONObject().apply {
                            put("usage", "verify")
                            put("certificate", JSONArray(bean.certificates.lines()))
                        })
                    })
                }
                // presence of echConfigList enables ECH; blank means "query DNS", leave that to sing-box
                if (bean.enableECH && bean.echConfig.isNotBlank()) {
                    put("echConfigList", bean.echConfig)
                }
            })
        }
    }
}
