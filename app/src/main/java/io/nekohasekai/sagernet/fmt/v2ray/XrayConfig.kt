package io.nekohasekai.sagernet.fmt.v2ray

import io.nekohasekai.sagernet.database.DataStore
import moe.matsuri.nb4a.utils.listByLineOrComma
import org.json.JSONArray
import org.json.JSONObject

// Builds an Xray-core client config for a VMess/VLESS profile:
// a local socks inbound chained from sing-box, and the profile as outbound.
// NOTE: ECH and newer REALITY fields (e.g. mldsa65Verify) are not mapped yet.
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
                    put("address", bean.serverAddress)
                    put("port", bean.serverPort)
                    put("users", JSONArray().apply { put(user) })
                })
            })
        })
        put("streamSettings", buildXrayStreamSettings(bean))
        // xudp rides on xray mux; packetaddr is not supported by xray
        if (bean.enableMux || bean.packetEncoding == 2) {
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
    }.toString(2)
}

private fun buildXrayStreamSettings(bean: VMessBean): JSONObject {
    return JSONObject().apply {
        // transport
        when (bean.type) {
            "ws" -> {
                put("network", "ws")
                put("wsSettings", JSONObject().apply {
                    if (bean.path.contains("?ed=")) {
                        put("path", bean.path.substringBefore("?ed="))
                        put("maxEarlyData", bean.path.substringAfter("?ed=").toIntOrNull() ?: 2048)
                        put("earlyDataHeaderName", "Sec-WebSocket-Protocol")
                    } else {
                        put("path", bean.path.takeIf { it.isNotBlank() } ?: "/")
                    }
                    if (bean.wsMaxEarlyData > 0) put("maxEarlyData", bean.wsMaxEarlyData)
                    if (bean.earlyDataHeaderName.isNotBlank()) {
                        put("earlyDataHeaderName", bean.earlyDataHeaderName)
                    }
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
        val fp = bean.utlsFingerprint.takeIf { it.isNotBlank() }
            ?: if (bean.realityPubKey.isNotBlank()) "chrome" else ""
        if (bean.realityPubKey.isNotBlank()) {
            put("security", "reality")
            put("realitySettings", JSONObject().apply {
                if (bean.sni.isNotBlank()) put("serverName", bean.sni)
                put("publicKey", bean.realityPubKey)
                if (bean.realityShortId.isNotBlank()) put("shortId", bean.realityShortId)
                if (fp.isNotBlank()) put("fingerprint", fp)
            })
        } else if (bean.security == "tls") {
            put("security", "tls")
            put("tlsSettings", JSONObject().apply {
                if (bean.sni.isNotBlank()) put("serverName", bean.sni)
                if (bean.alpn.isNotBlank()) {
                    put("alpn", JSONArray(bean.alpn.listByLineOrComma()))
                }
                if (bean.allowInsecure || DataStore.globalAllowInsecure) {
                    put("allowInsecure", true)
                }
                if (fp.isNotBlank()) put("fingerprint", fp)
                if (bean.certificates.isNotBlank()) {
                    put("certificates", JSONArray().apply {
                        put(JSONObject().apply {
                            put("usage", "verify")
                            put("certificate", JSONArray(bean.certificates.lines()))
                        })
                    })
                }
            })
        }
    }
}
