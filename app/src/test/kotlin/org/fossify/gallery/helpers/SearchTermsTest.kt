package org.fossify.gallery.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTermsTest {
    private fun n(s: String) = TextNormalizer.normalize(s, true)

    private fun matches(name: String, query: String) =
        SearchTerms.meaningful(SearchTerms.split(n(query))).any { SearchTerms.nameMatches(n(name), it) }

    @Test
    fun conjunctionsDoNotMatchPeople() {
        val people = listOf("Aleš", "Mária", "Ján Novák", "Peter", "Lukáš", "Hana")
        assertEquals(listOf("pes", "macka"), SearchTerms.meaningful(SearchTerms.split(n("pes a mačka"))))
        people.forEach { assertFalse(it, matches(it, "pes a mačka")) }
        people.forEach { assertFalse(it, matches(it, "na pláži s loďou v lete")) }
    }

    @Test
    fun substringsAreNotNames() {
        assertFalse(matches("Aleš", "les"))
        assertFalse(matches("Hana", "han"))
        assertFalse(matches("Ida", "id"))
        assertFalse(matches("Peter", "pet"))
        assertFalse(matches("Martina", "tina"))
    }

    @Test
    fun wholeWordsAndSlovakFormsMatch() {
        assertTrue(matches("Aleš", "Aleš"))
        assertTrue(matches("Aleš", "ales"))
        assertTrue(matches("Aleš", "s Alešom"))
        assertTrue(matches("Peter", "Petra"))
        assertTrue(matches("Peter", "Petrovi"))
        assertTrue(matches("Peter", "s Petrom"))
        assertTrue(matches("Mária", "Márie"))
        assertTrue(matches("Mária", "s Máriou"))
        assertTrue(matches("Lukáš", "Lukášove fotky"))
        assertTrue(matches("Ján Novák", "novak"))
        assertTrue(matches("Eva", "Evu"))
        assertTrue(matches("Marek", "Marka"))
    }

    @Test
    fun monthsMatchOnlyExactName() {
        assertTrue(SearchTerms.isMonth(n("máj")))
        assertTrue(SearchTerms.isMonth(n("júna")))
        assertFalse(matches("Maja", "máj"))
        assertTrue(matches("Maja", "Maja"))
        assertFalse(matches("Júlia", "júl"))
    }

    @Test
    fun qrTokensSkipShortWordsAndMonths() {
        assertEquals(listOf("wifi"), SearchTerms.forQr(SearchTerms.split(n("a x wifi v máji"))))
    }
}
