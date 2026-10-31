package ru.danil.medtracker.calc

import java.math.BigInteger

/**
 * Точная дробь для расчётов «обеспечен до» (п. 4.1 ТЗ: только точные дроби, не double).
 * Случаи 4 и Б1 Приложения А заканчиваются ровно на нуле и ломаются на float.
 */
class Rational private constructor(val n: BigInteger, val d: BigInteger) : Comparable<Rational> {

    companion object {
        val ZERO = Rational(BigInteger.ZERO, BigInteger.ONE)
        val ONE = Rational(BigInteger.ONE, BigInteger.ONE)
        fun of(n: Long, d: Long = 1L): Rational =
            of(BigInteger.valueOf(n), BigInteger.valueOf(d))
        fun of(n: BigInteger, d: BigInteger): Rational {
            require(d.signum() != 0) { "Знаменатель равен нулю" }
            var num = n
            var den = d
            if (den.signum() < 0) {
                num = num.negate()
                den = den.negate()
            }
            val g = num.gcd(den)
            if (g.signum() != 0 && g != BigInteger.ONE) {
                num = num.divide(g)
                den = den.divide(g)
            }
            return Rational(num, den)
        }
    }

    operator fun plus(o: Rational) = Rational.of(n.multiply(o.d).add(o.n.multiply(d)), d.multiply(o.d))
    operator fun minus(o: Rational) = Rational.of(n.multiply(o.d).subtract(o.n.multiply(d)), d.multiply(o.d))
    operator fun times(o: Rational) = Rational.of(n.multiply(o.n), d.multiply(o.d))
    operator fun div(o: Rational) = Rational.of(n.multiply(o.d), d.multiply(o.n))

    fun isZero() = n.signum() == 0
    fun isNegative() = n.signum() < 0
    fun negate() = Rational.of(n.negate(), d)

    /** Целая часть с отбрасыванием дробной (вниз к нулю). */
    fun truncate(): BigInteger = n.divide(d)

    override fun compareTo(other: Rational): Int =
        n.multiply(other.d).compareTo(other.n.multiply(d))

    override fun equals(other: Any?): Boolean =
        other is Rational && n == other.n && d == other.d

    override fun hashCode(): Int = n.hashCode() * 31 + d.hashCode()

    override fun toString(): String =
        if (d == BigInteger.ONE) n.toString() else "$n/$d"

    /** Человекочитаемо: до 2 знаков без хвостовых нулей. */
    fun toDisplay(): String {
        if (d == BigInteger.ONE) return n.toString()
        val scaled = n.multiply(BigInteger.valueOf(100)).divide(d)
        val intPart = scaled.divide(BigInteger.valueOf(100))
        val frac = scaled.mod(BigInteger.valueOf(100))
        if (frac.signum() == 0) return intPart.toString()
        var fs = frac.toString().padStart(2, '0').trimEnd('0')
        return "$intPart,$fs"
    }
}
