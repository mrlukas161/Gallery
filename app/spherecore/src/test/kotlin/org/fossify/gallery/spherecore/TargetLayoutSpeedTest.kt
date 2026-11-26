package org.fossify.gallery.spherecore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rozloženie cieľov sa v mobile počíta pred prvým záberom, preto musí byť rýchle a výsledok stály. */
class TargetLayoutSpeedTest {
    @Test fun countsAndSpeed() {
        // (h, v) → očakávaný počet cieľov (hodnoty pred zrýchlením hľadania; zmena = zmena správania)
        val cases = listOf(
            Triple(58.9, 73.9, 33), Triple(85.6, 102.0, 19), Triple(45.0, 60.0, 46),
            Triple(66.0, 82.0, 28), Triple(50.0, 65.0, 40),
        )
        for ((h, v, n) in cases) {
            val t0 = System.nanoTime()
            val t = TargetLayout.generate(h + 0.001, v + 0.001) // mimo vyrovnávacej pamäte
            val ms = (System.nanoTime() - t0) / 1e6
            println("TargetLayout %.1f×%.1f → %d cieľov za %.0f ms".format(h, v, t.size, ms))
            assertEquals("počet cieľov pre $h×$v", n, t.size)
            assertTrue("pomalé: $ms ms", ms < 1500)
        }
        // úzky objektív (emulátor 40×52): najhorší prípad, hodnotia sa všetci kandidáti
        val t0 = System.nanoTime()
        val t = TargetLayout.generate(39.97, 51.73)
        println("TargetLayout 40×52 → %d cieľov za %.0f ms".format(t.size, (System.nanoTime() - t0) / 1e6))
    }
}
