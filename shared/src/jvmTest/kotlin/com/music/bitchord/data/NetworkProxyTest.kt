package com.music.bitchord.data

import java.net.Proxy
import java.net.URI
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkProxyTest {
    @AfterTest
    fun reset() = NetworkProxy.update(null)

    @Test
    fun youtubeHostsAreProxiedAndOthersAreNot() {
        assertTrue(NetworkProxy.routesThroughProxy("music.youtube.com"))
        assertTrue(NetworkProxy.routesThroughProxy("rr1---sn-abc.googlevideo.com"))
        assertTrue(NetworkProxy.routesThroughProxy("i.ytimg.com"))
        assertFalse(NetworkProxy.routesThroughProxy("notyoutube.com"))
        assertFalse(NetworkProxy.routesThroughProxy("lrclib.net"))
    }

    @Test
    fun selectorGoesDirectWithoutConfig() {
        assertEquals(listOf(Proxy.NO_PROXY), NetworkProxy.selector.select(URI("https://music.youtube.com/")))
    }

    @Test
    fun selectorUsesProxyOnlyForYoutube() {
        NetworkProxy.update(NetworkProxy.Config("10.0.0.1", 3128))
        val proxied = NetworkProxy.selector.select(URI("https://music.youtube.com/"))
        assertEquals(Proxy.Type.HTTP, proxied.single().type())
        assertEquals(listOf(Proxy.NO_PROXY), NetworkProxy.selector.select(URI("https://lrclib.net/")))
    }
}
