package com.music.bitchord.data

import android.content.Context
import android.webkit.HttpAuthHandler
import androidx.core.content.ContextCompat
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.music.bitchord.data.DebugLog as Log

/**
 * Carries [NetworkProxy] over to WebView, which has its own network stack and
 * never sees [Http.client]'s proxy selector.
 *
 * Applied on demand, right before a WebView that loads YouTube is created,
 * rather than at startup: [ProxyController] loads the WebView provider, which
 * is a cost a cold start does not need to pay.
 *
 * The override is process-wide — every WebView in the app sees it, including
 * the hidden PO-token one and the Discord and Spotify sign-ins, none of which
 * have any business going through it. So it is held only while the screen that
 * asked for it is up: [apply] on the way in, [release] on the way out.
 */
object WebViewProxy {
    private const val TAG = "WebViewProxy"

    /** What was last handed to [ProxyController], so an unchanged config is not re-applied. */
    @Volatile private var applied: NetworkProxy.Config? = null

    /**
     * Applies the current [NetworkProxy.config] and calls [onReady] on the main
     * thread once WebView will honour it. Without WebView support for proxy
     * overrides [onReady] runs straight away and the WebView connects directly.
     */
    fun apply(context: Context, onReady: () -> Unit) {
        val config = NetworkProxy.config
        if (config == applied || !WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            onReady()
            return
        }
        val executor = ContextCompat.getMainExecutor(context)
        val controller = ProxyController.getInstance()
        val done = Runnable {
            applied = config
            onReady()
        }
        runCatching {
            if (config == null) {
                controller.clearProxyOverride(executor, done)
            } else {
                controller.setProxyOverride(build(config), executor, done)
            }
        }.onFailure {
            Log.w(TAG, "proxy override failed: ${it.message}")
            onReady()
        }
    }

    /**
     * Takes the override off again, so WebViews opened after the one that
     * asked for it connect as they always did. A no-op if none was applied.
     */
    fun release(context: Context) {
        if (applied == null) return
        applied = null
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) return
        runCatching {
            ProxyController.getInstance().clearProxyOverride(ContextCompat.getMainExecutor(context)) {}
        }.onFailure { Log.w(TAG, "clearing proxy override failed: ${it.message}") }
    }

    private fun build(config: NetworkProxy.Config): ProxyConfig {
        val builder = ProxyConfig.Builder().addProxyRule("${config.host}:${config.port}")
        // Reverse bypass turns the bypass list into the list of hosts that *do*
        // use the proxy, which is what lets WebView proxy YouTube alone. A
        // WebView too old for it proxies everything instead — still right for
        // the YouTube pages, just less frugal.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE_REVERSE_BYPASS)) {
            NetworkProxy.PROXIED_DOMAINS.forEach { domain ->
                builder.addBypassRule(domain)
                builder.addBypassRule("*.$domain")
            }
            builder.setReverseBypassEnabled(true)
        }
        return builder.build()
    }

    /**
     * Answers the proxy's own 407 from a WebViewClient's
     * `onReceivedHttpAuthRequest`, which WebView raises for proxy challenges
     * as well as for sites'. Returns false when the challenge is not the
     * proxy's, or there are no credentials to give it. [attempt] counts from
     * 1; a second challenge means the credentials were refused, and is
     * cancelled rather than answered with them again forever.
     */
    fun handleAuthRequest(handler: HttpAuthHandler, host: String?, attempt: Int): Boolean {
        val config = NetworkProxy.config ?: return false
        if (!config.hasCredentials || !host.equals(config.host, ignoreCase = true)) return false
        if (attempt > 1) handler.cancel() else handler.proceed(config.username, config.password)
        return true
    }
}
