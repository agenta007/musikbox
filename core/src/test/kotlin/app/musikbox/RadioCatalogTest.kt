package app.musikbox

import app.musikbox.radio.RadioCatalog
import app.musikbox.radio.RadioCategory
import app.musikbox.radio.RadioDial
import app.musikbox.radio.flagOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioCatalogTest {
    private val sample = """
        #musikbox-radio	1	2026-11-23
        C	BG	Bulgaria
        C	DE	Germany
        G	0	Pop
        G	1	Jazz & Blues
        S	a	Energy NRJ Bulgaria	http://play.global.audio/nrj128	https://logo/nrj.png	BG	MP3	128	0	0	12193
        S	b	Jazz FM Bulgaria	http://jazz.bg/stream		BG	AAC	64	0	1	50
        S	c	Radio Bremen Jazz	https://bremen.de/jazz.m3u8		DE		0	1	1	900
        S	d	Pop Deutschland	https://pop.de/live		DE	MP3	192	0	0	30000
    """.trimIndent()

    private val catalog = RadioCatalog.parse(sample.byteInputStream())

    @Test fun parsesTheFormat() {
        assertEquals(listOf("Bulgaria", "Germany"), catalog.countries.map { it.name })
        assertEquals(listOf(2, 2), catalog.countries.map { it.stations })
        val jazz = catalog.station("c")!!
        assertEquals(listOf("Jazz & Blues"), jazz.genres)
        assertTrue(jazz.hls)
        assertEquals(null, jazz.logo)
        assertEquals("", jazz.quality)
        assertEquals("MP3 128k", catalog.station("a")!!.quality)
        assertEquals("2026-11-23", catalog.generated)
    }

    @Test fun categories() {
        assertEquals(listOf("a", "b"), catalog.inCountry("BG").map { it.id })
        // the home country first, then the most voted
        assertEquals(listOf("b", "c"), catalog.inGenre("Jazz & Blues", "BG").map { it.id })
        assertEquals(listOf("d", "a"), catalog.inGenre("Pop", null).map { it.id })
        assertEquals(listOf("d", "a"), catalog.stations(RadioCategory.Favorites, listOf("d", "gone", "a"), null).map { it.id })
        assertEquals(RadioCategory.Genre("Jazz & Blues"), RadioCategory.parse(RadioCategory.Genre("Jazz & Blues").key))
        assertEquals(RadioCategory.Country("BG"), RadioCategory.parse("country:BG"))
        assertEquals(listOf("DE", "BG"), catalog.countriesFor("DE").map { it.code })
    }

    @Test fun searchByWordsOfTheName() {
        assertEquals(listOf("b", "c"), catalog.search("jazz", "BG").map { it.id })
        assertEquals(listOf("c"), catalog.search("JAZZ bremen", null).map { it.id })
        assertTrue(catalog.search("   ", null).isEmpty())
    }

    @Test fun theBundledCatalogLoads() {
        val real = RadioCatalog.load()
        assertTrue("${real.stations.size} stations", real.stations.size > 30_000)
        assertTrue(real.countries.size > 200)
        assertNotNull(real.country("BG"))
        assertTrue(real.inCountry("BG").size > 200)
        assertTrue(real.genres.any { it.name == "Pop" && it.stations > 1000 })
        assertTrue(real.stations.all { it.url.startsWith("http") })
    }

    @Test fun flagsAndDial() {
        assertEquals("🇧🇬", flagOf("BG"))
        assertEquals("XYZ", flagOf("XYZ"))
        assertEquals(87.5, RadioDial.frequency(0f, 10), 1e-9)
        assertEquals(108.0, RadioDial.frequency(9f, 10), 1e-9)
        assertEquals("87.5", RadioDial.label(0, 100))
        assertEquals("108.00", RadioDial.label(999, 1000))
        assertEquals("98.0", RadioDial.label(0, 1))
    }
}
