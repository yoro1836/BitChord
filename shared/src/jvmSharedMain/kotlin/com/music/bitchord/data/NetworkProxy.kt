package com.music.bitchord.data

import okhttp3.Authenticator
import okhttp3.Credentials
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * An optional HTTP proxy for YouTube's own traffic, for listeners on a network
 * that cannot reach YouTube Music directly.
 *
 * Only YouTube's hosts go through it ([routesThroughProxy]); lyrics, artwork
 * providers, scrobblers and the rest keep connecting directly, so the proxy
 * carries only what needs it. That set has to include googlevideo.com and not
 * just the API: a stream URL is bound to the address of the `player` request
 * that minted it (see [Http]), so resolving through the proxy and then
 * fetching the audio directly would come back 403.
 *
 * An HTTP proxy in the usual sense: plain requests are forwarded, HTTPS ones
 * are tunnelled with CONNECT, so the proxy never sees inside TLS. Speaking TLS
 * *to* the proxy itself is not something OkHttp supports.
 *
 * Held in memory like [com.music.bitchord.data.webdav.WebDavAuth]: the app's
 * settings publish here on start and on every edit, and [Http.client] reads it
 * per connection, so a change applies without rebuilding the client.
 */
object NetworkProxy {

    data class Config(
        val host: String,
        val port: Int,
        val username: String = "",
        val password: String = "",
    ) {
        val hasCredentials: Boolean get() = username.isNotEmpty()
    }

    @Volatile
    var config: Config? = null
        private set

    /** Pass null to connect directly again. */
    fun update(config: Config?) {
        val previous = this.config
        this.config = config
        // Pooled connections keep the route they were opened on, so without
        // this a YouTube connection opened before the change would go on being
        // reused after it. Only idle ones can be closed; one mid-stream finishes
        // on the route it started with.
        if (previous != config) Http.client.connectionPool.evictAll()
    }

    /**
     * The hosts that go through the proxy: YouTube's pages and API, the CDN
     * its audio and thumbnails are served from, and the Google endpoints its
     * sign-in and PO token go through. Each entry covers its subdomains.
     */
    val PROXIED_DOMAINS: List<String> = listOf(
        "youtube.com",
        "youtu.be",
        "youtube-nocookie.com",
        "youtubei.googleapis.com",
        "youtube.googleapis.com",
        "jnn-pa.googleapis.com",
        "googlevideo.com",
        "ytimg.com",
        "ggpht.com",
        "googleusercontent.com",
        "accounts.google.com",
    )

    fun routesThroughProxy(host: String): Boolean {
        val h = host.trimEnd('.').lowercase()
        return PROXIED_DOMAINS.any { h == it || h.endsWith(".$it") }
    }

    /**
     * Dynamic, so [Http.client] can be built once. An empty answer tells
     * OkHttp to connect directly; a proxied host that cannot reach the proxy
     * fails rather than quietly falling back to the direct route.
     */
    val selector: ProxySelector = object : ProxySelector() {
        override fun select(uri: URI?): List<Proxy> {
            val current = config ?: return DIRECT
            val host = uri?.host ?: return DIRECT
            if (!routesThroughProxy(host)) return DIRECT
            return listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(current.host, current.port)))
        }

        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
    }

    /**
     * Basic credentials for the proxy. OkHttp asks before opening an HTTPS
     * tunnel (a preemptive challenge) and again on a real 407; a request that
     * already carried them and still got a 407 is answered with null so a
     * wrong password fails instead of looping.
     */
    val authenticator: Authenticator = Authenticator { _, response ->
        val current = config
        when {
            current == null || !current.hasCredentials -> null
            response.request.header(PROXY_AUTHORIZATION) != null -> null
            else -> response.request.newBuilder()
                .header(PROXY_AUTHORIZATION, Credentials.basic(current.username, current.password))
                .build()
        }
    }

    private const val PROXY_AUTHORIZATION = "Proxy-Authorization"
    private val DIRECT = listOf(Proxy.NO_PROXY)
}
