package io.nekohasekai.sagernet.bg.proto

import android.os.SystemClock
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.bg.GuardedProcessPool
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.mkPort
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.tryResume
import io.nekohasekai.sagernet.ktx.tryResumeWithException
import kotlinx.coroutines.delay
import libcore.Libcore
import moe.matsuri.nb4a.net.LocalResolverImpl
import moe.matsuri.nb4a.proxy.anytls.MIHOMO_PROXY_NAME
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.suspendCoroutine

class TestInstance(profile: ProxyEntity, val link: String, private val timeout: Int) :
    BoxInstance(profile) {

    // Single-node AnyTLS-on-mihomo: enable mihomo's Clash API so mihomo measures
    // the delay through the proxy itself. Chained profiles keep the sing-box path.
    private val mihomoController: Pair<Int, String>? by lazy {
        if (profile.type != ProxyEntity.TYPE_ANYTLS || !profile.needExternal()) return@lazy null
        val group = SagerDatabase.groupDao.getById(profile.groupId) ?: return@lazy null
        if (group.frontProxy > 0 || group.landingProxy > 0) return@lazy null
        mkPort() to UUID.randomUUID().toString().replace("-", "")
    }

    override fun mihomoTestController(): Pair<Int, String>? = mihomoController

    suspend fun doTest(): Int {
        return suspendCoroutine { c ->
            processes = GuardedProcessPool {
                Logs.w(it)
                c.tryResumeWithException(it)
            }
            runOnDefaultDispatcher {
                use {
                    try {
                        init()
                        launch()
                        val controller = mihomoController
                        if (controller != null) {
                            c.tryResume(mihomoDelay(controller))
                        } else {
                            if (processes.processCount > 0) {
                                // wait for plugin start
                                delay(500)
                            }
                            c.tryResume(Libcore.urlTest(box, link, timeout))
                        }
                    } catch (e: Exception) {
                        c.tryResumeWithException(e)
                    }
                }
            }
        }
    }

    // Poll mihomo's Clash API until the core is up (replaces a fixed startup delay),
    // then let mihomo measure the delay through the proxy:
    // mihomo -> sing-box mapping inbound -> real server.
    private fun mihomoDelay(controller: Pair<Int, String>): Int {
        val (port, secret) = controller
        val client = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(timeout + 3000L, TimeUnit.MILLISECONDS)
            .build()
        val base = "http://127.0.0.1:$port"

        fun newRequest(url: String) = Request.Builder().url(url)
            .header("Authorization", "Bearer $secret")
            .build()

        val deadline = SystemClock.elapsedRealtime() + 5000
        var ready = false
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                client.newCall(newRequest("$base/version")).execute().use { resp ->
                    ready = resp.isSuccessful
                }
            } catch (_: IOException) {
            }
            if (ready) break
            Thread.sleep(100)
        }
        if (!ready) throw IOException("mihomo controller not ready")

        val url = "$base/proxies/$MIHOMO_PROXY_NAME/delay" +
            "?url=${URLEncoder.encode(link, "UTF-8")}&timeout=$timeout"
        client.newCall(newRequest(url)).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (resp.isSuccessful) return JSONObject(body).getInt("delay")
            val message = runCatching { JSONObject(body).getString("message") }.getOrNull()
            throw IOException(message ?: "mihomo delay test failed: HTTP ${resp.code}")
        }
    }

    override fun buildConfig() {
        config = buildConfig(profile, true)
    }

    override suspend fun loadConfig() {
        // don't call destroyAllJsi here
        if (BuildConfig.DEBUG) Logs.d(config.config)
        box = Libcore.newSingBoxInstance(config.config, LocalResolverImpl)
    }

}
