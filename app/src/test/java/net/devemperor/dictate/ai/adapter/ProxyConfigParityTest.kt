package net.devemperor.dictate.ai.adapter

import net.devemperor.dictate.DictateUtils
import net.devemperor.dictate.preferences.Pref
import net.devemperor.dictate.preferences.put
import net.devemperor.dictate.testutil.FakeSharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * Characterization test for [SharedPrefsProxyConfig]: `rawProxy()` reproduces
 * `DictateUtils.createProxy` exactly across proxy-on/off × http/socks5 ×
 * with/without credentials — the proxy path is the one that breaks silently in
 * the runners when a move drifts (spec §8.1).
 *
 * `applyTo` (the SDK-builder variants) can't be introspected without a live
 * client, so it is exercised only for "does not throw"; the load-bearing,
 * observable contract is `rawProxy()`, which the raw-okhttp ElevenLabs runner
 * consumes directly.
 */
class ProxyConfigParityTest {

    private val sp = FakeSharedPreferences()
    private val proxyConfig = SharedPrefsProxyConfig(sp)

    @After
    fun tearDown() {
        // installAuthenticator sets a process-wide default — reset to avoid leaking.
        Authenticator.setDefault(null)
    }

    @Test
    fun `rawProxy is null when proxy disabled`() {
        sp.edit()
            .put(Pref.ProxyEnabled, false)
            .put(Pref.ProxyHost, "http://1.2.3.4:8080")
            .apply()
        assertNull(proxyConfig.rawProxy())
        assertNull(DictateUtils.createProxy(sp))
    }

    @Test
    fun `rawProxy is null when host invalid`() {
        sp.edit()
            .put(Pref.ProxyEnabled, true)
            .put(Pref.ProxyHost, "not a proxy")
            .apply()
        assertNull(proxyConfig.rawProxy())
    }

    @Test
    fun `rawProxy resolves http proxy identical to createProxy`() {
        sp.edit()
            .put(Pref.ProxyEnabled, true)
            .put(Pref.ProxyHost, "http://1.2.3.4:8080")
            .apply()

        val proxy = proxyConfig.rawProxy()!!
        assertEquals(DictateUtils.createProxy(sp), proxy)
        assertEquals(Proxy.Type.HTTP, proxy.type())
        val addr = proxy.address() as InetSocketAddress
        assertEquals("1.2.3.4", addr.hostString)
        assertEquals(8080, addr.port)
    }

    @Test
    fun `rawProxy resolves socks5 proxy`() {
        sp.edit()
            .put(Pref.ProxyEnabled, true)
            .put(Pref.ProxyHost, "socks5://10.0.0.1:1080")
            .apply()

        val proxy = proxyConfig.rawProxy()!!
        assertEquals(DictateUtils.createProxy(sp), proxy)
        assertEquals(Proxy.Type.SOCKS, proxy.type())
    }

    @Test
    fun `rawProxy resolves host with credentials`() {
        sp.edit()
            .put(Pref.ProxyEnabled, true)
            .put(Pref.ProxyHost, "http://user:pass@1.2.3.4:8080")
            .apply()

        val proxy = proxyConfig.rawProxy()!!
        assertEquals(DictateUtils.createProxy(sp), proxy)
        assertEquals(Proxy.Type.HTTP, proxy.type())
        // installAuthenticator must not throw (mirrors the runner's post-proxy call).
        proxyConfig.installAuthenticator()
    }

    @Test
    fun `rawProxy is null when host is a bad IPv4`() {
        sp.edit()
            .put(Pref.ProxyEnabled, true)
            .put(Pref.ProxyHost, "http://999.1.1.1:8080")
            .apply()
        assertNull(proxyConfig.rawProxy())
    }
}
