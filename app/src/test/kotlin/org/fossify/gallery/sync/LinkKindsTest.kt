package org.fossify.gallery.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkKindsTest {
    @Test
    fun usbTetheringInterfaces() {
        for (n in listOf("rndis0", "usb0", "ncm0", "NCM1")) assertEquals(n, LinkKind.USB, LinkKinds.kindOf(n))
    }

    @Test
    fun wifiHotspotEthernet() {
        assertEquals(LinkKind.WIFI, LinkKinds.kindOf("wlan0"))
        for (n in listOf("ap0", "swlan0", "softap0", "wlan1")) assertEquals(n, LinkKind.HOTSPOT, LinkKinds.kindOf(n))
        assertEquals(LinkKind.ETHERNET, LinkKinds.kindOf("eth0"))
        assertEquals(LinkKind.OTHER, LinkKinds.kindOf("br0"))
    }

    @Test
    fun mobileDataVpnAndHelpersAreNeverOffered() {
        val never = listOf("rmnet_data0", "ccmni1", "tun0", "dummy0", "lo", "v4-rmnet_data0", "p2p0", "ipsec1", "clat4")
        for (n in never) {
            assertNull(n, LinkKinds.kindOf(n))
        }
    }

    @Test
    fun orderUsbCableFirstThenWifiThenHotspot() {
        val found = listOf(LinkKind.HOTSPOT to "a", LinkKind.USB to "b", LinkKind.WIFI to "c", LinkKind.USB to "d")
        val s = LinkKinds.sorted(found)
        assertEquals(listOf("b", "d", "c", "a"), s.map { it.second })
        assertEquals("http://192.168.42.129:8089", LinkKinds.url("192.168.42.129", 8089))
    }
}
