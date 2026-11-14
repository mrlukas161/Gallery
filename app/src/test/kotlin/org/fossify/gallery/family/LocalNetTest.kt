package org.fossify.gallery.family

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Revízia 11/2026: párovací odkaz a server rodinného prepojenia len v lokálnej sieti; overovací kód páru.
class LocalNetTest {

    @Test
    fun privateIpv4RangesAreLocal() {
        listOf("10.0.0.1", "10.255.255.254", "172.16.0.1", "172.31.200.3", "192.168.43.1", "169.254.1.2")
            .forEach { assertTrue(it, LocalNet.isLocal(it)) }
    }

    @Test
    fun publicAddressesAndNamesAreNotLocal() {
        listOf("8.8.8.8", "172.15.0.1", "172.32.0.1", "192.169.1.1", "100.64.0.1", "1.2.3", "1.2.3.4.5", "256.1.1.1")
            .forEach { assertFalse(it, LocalNet.isLocal(it)) }
        listOf("evil.example.com", "localhost", "", " ", "10.0.0.1.example.com")
            .forEach { assertFalse(it, LocalNet.isLocal(it)) }
    }

    @Test
    fun loopbackOnlyWhenAllowed() {
        assertFalse(LocalNet.isLocal("127.0.0.1"))
        assertTrue(LocalNet.isLocal("127.0.0.1", allowLoopback = true))
        assertFalse(LocalNet.isLocal("::1"))
        assertTrue(LocalNet.isLocal("::1", allowLoopback = true))
    }

    @Test
    fun ipv6LinkLocalAndUniqueLocal() {
        assertTrue(LocalNet.isLocal("fe80::1"))
        assertTrue(LocalNet.isLocal("fe80::a00:27ff:fe4e:66a1%wlan0"))
        assertTrue(LocalNet.isLocal("[fd00::5]"))
        assertTrue(LocalNet.isLocal("FC00::1"))
        assertFalse(LocalNet.isLocal("2001:db8::1"))
        assertFalse(LocalNet.isLocal("fec0::1"))
        // IPv4 v IPv6 zápise (NanoHTTPD môže tak hlásiť adresu klienta)
        assertTrue(LocalNet.isLocal("::ffff:192.168.1.5"))
        assertFalse(LocalNet.isLocal("::ffff:8.8.8.8"))
    }

    @Test
    fun ipv4Parsing() {
        assertEquals(listOf(192, 168, 1, 5), LocalNet.ipv4("192.168.1.5")!!.toList())
        assertNull(LocalNet.ipv4("192.168.1"))
        assertNull(LocalNet.ipv4("a.b.c.d"))
        assertNull(LocalNet.ipv4("1.2.3.-4"))
    }

    // obe strany odvodia rovnaký 4-ciferný kód z kľúča páru; iný kľúč = (takmer vždy) iný kód
    @Test
    fun verificationCodeIsFourDigitsAndBoundToKey() {
        val key = ByteArray(FamilyCrypto.KEY_BYTES) { it.toByte() }
        val code = FamilyCrypto.verificationCode(key)
        assertEquals(4, code.length)
        assertTrue(code.all { it.isDigit() })
        assertEquals(code, FamilyCrypto.verificationCode(key.copyOf()))
        val other = ByteArray(FamilyCrypto.KEY_BYTES) { (it + 1).toByte() }
        assertNotEquals(code, FamilyCrypto.verificationCode(other))
        // kódy sú rozložené (nie stále rovnaké číslo)
        val codes = (0 until 50).map { i ->
            FamilyCrypto.verificationCode(ByteArray(FamilyCrypto.KEY_BYTES) { i.toByte() })
        }
        assertTrue(codes.toSet().size > 40)
    }
}
