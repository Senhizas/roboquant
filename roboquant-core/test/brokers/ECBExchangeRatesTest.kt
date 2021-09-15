package org.roboquant.brokers

import org.roboquant.TestData
import org.junit.Test
import kotlin.test.*
import org.roboquant.common.Currency
import java.time.Instant

internal class ECBExchangeRatesTest {

    @Test
    fun testECBReferenceRates() {
        val fileName = TestData.dataDir() + "RATES/eurofxref-hist.csv"
        val x = ECBExchangeRates.fromFile(fileName)

        val eur = Currency.getInstance("EUR")
        var c = x.convert(eur, eur, 100.0, Instant.now())
        assertEquals(100.0, c)

        val usd = Currency.getInstance("USD")
        c = x.convert(usd, eur, 100.0, Instant.now())
        assertTrue(c < 100.0)

        val jpy = Currency.getInstance("JPY")
        c = x.convert(eur, jpy, 100.0, Instant.now())
        assertTrue(c > 100.0)

        assertFails {
            c = x.convert(usd, jpy, 100.0, Instant.MIN)
        }

        c = x.convert(usd, jpy, 100.0, Instant.MAX)
        assertTrue(c > 100.0)

        val c1 = x.convert(usd, jpy, 100.0, Instant.now())
        val c2 = x.convert(jpy, usd, c1, Instant.now())
        assertEquals(100.0, c2)

        val currencies = x.currencies
        assertTrue(jpy in currencies)

        assertTrue(eur !in currencies)

    }

}