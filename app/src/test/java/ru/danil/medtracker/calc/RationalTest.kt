package ru.danil.medtracker.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RationalTest {

    @Test
    fun normalization() {
        assertEquals("1/2", Rational.of(2, 4).toString())
        assertEquals("-1/2", Rational.of(1, -2).toString())
        assertEquals("3", Rational.of(9, 3).toString())
    }

    @Test
    fun exactArithmetic() {
        // 29/30 + 1/30 = 1 — случай, ломающий double
        val sum = Rational.of(29, 30) + Rational.of(1, 30)
        assertEquals(Rational.ONE, sum)
    }

    @Test
    fun compareExact() {
        // 1/30 >= 1/31 — на double тоже true, но 0.1+0.2 != 0.3
        assertTrue(Rational.of(1, 30) > Rational.of(1, 31))
        assertTrue(Rational.of(1, 3) * Rational.of(3) == Rational.ONE)
    }

    @Test
    fun floorRoundUp() {
        assertEquals(0L, Rational.of(29, 30).truncate().toLong())
        assertEquals("1,45", (Rational.of(29, 20)).toDisplay())
        assertEquals("2", Rational.of(2).toDisplay())
    }
}
