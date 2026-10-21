package com.hindsight.app.money

import java.math.BigInteger
import java.security.MessageDigest

/**
 * Minimal, dependency-free Ed25519 signature *verification* (RFC 8032),
 * implemented over [BigInteger] field arithmetic.
 *
 * Why in-tree: the Shell Verifier (AC-01) must verify the wallet's
 * detached message signature against the connected wallet's public key
 * on every Android version this app ships to (minSdk 28), and neither
 * the platform `KeyFactory` nor the MWA client library guarantees an
 * Ed25519 verifier across that range. This verifier is small, has no
 * signing capability (it can never be turned into a signing oracle),
 * and is pinned by the RFC 8032 test vectors in the verification
 * harness (`verification/MoneyBuildHarness.kt`).
 *
 * Verification is cofactorless ([S]B == R + [H(R,A,M)]A) with
 * canonical-encoding and small-order rejection for both the public
 * key and R, per RFC 8032 §5.1.7 and the ZIP-215-adjacent strictness
 * appropriate for an identity binding check.
 */
object Ed25519 {
    private val P: BigInteger = BigInteger.TWO.pow(255).subtract(BigInteger.valueOf(19))
    private val L: BigInteger = BigInteger.TWO.pow(252)
        .add(BigInteger("27742317777372353535851937790883648493"))
    private val D: BigInteger = run {
        val num = BigInteger.valueOf(-121665).mod(P)
        val den = BigInteger.valueOf(121666).modInverse(P)
        num.multiply(den).mod(P)
    }
    private val I: BigInteger = BigInteger.TWO.modPow(P.subtract(BigInteger.ONE).divide(BigInteger.valueOf(4)), P)
    private val BY: BigInteger = BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(P)).mod(P)
    private val BX: BigInteger = recoverX(BY, false)!!

    private data class Point(val x: BigInteger, val y: BigInteger)

    private val IDENTITY = Point(BigInteger.ZERO, BigInteger.ONE)
    private val BASE = Point(BX, BY)

    /**
     * A point is small-order iff multiplying it by the cofactor (8)
     * reaches the identity — the mathematical form of the usual
     * encoding blacklist, with no hand-typed constants to get wrong.
     */
    /** True iff [encoded] is a valid curve point encoding. Used by
     *  program-derived-address derivation (a PDA must be OFF-curve). */
    fun isOnCurve(encoded: ByteArray): Boolean {
        if (encoded.size != 32) return false
        return runCatching { decodePoint(encoded) != null }.getOrDefault(false)
    }

    private fun isSmallOrder(point: Point): Boolean {
        val eightTimes = scalarMultiply(point, BigInteger.valueOf(8))
        return eightTimes.x == IDENTITY.x && eightTimes.y == IDENTITY.y
    }

    /**
     * Verify [signature] (64 bytes: R || S) over [message] for the
     * 32-byte encoded public key [publicKey]. Any malformed input is
     * a plain `false` — this function never throws.
     */
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean =
        runCatching {
            if (publicKey.size != 32 || signature.size != 64) return false
            val a = decodePoint(publicKey) ?: return false
            if (isSmallOrder(a)) return false
            val rEncoded = signature.copyOfRange(0, 32)
            val r = decodePoint(rEncoded) ?: return false
            if (isSmallOrder(r)) return false
            val s = littleEndianToBigInt(signature.copyOfRange(32, 64))
            if (s >= L || s.signum() < 0) return false
            val h = sha512(rEncoded + publicKey + message)
            val k = littleEndianToBigInt(h).mod(L)
            val left = scalarMultiply(BASE, s)
            val right = pointAdd(r, scalarMultiply(a, k))
            left.x == right.x && left.y == right.y
        }.getOrDefault(false)

    private fun decodePoint(encoded: ByteArray): Point? {
        val yWithSign = littleEndianToBigInt(encoded)
        val sign = yWithSign.testBit(255)
        val y = yWithSign.clearBit(255)
        if (y >= P) return null
        val x = recoverX(y, sign) ?: return null
        return Point(x, y)
    }

    private fun recoverX(y: BigInteger, sign: Boolean): BigInteger? {
        val y2 = y.multiply(y).mod(P)
        val u = y2.subtract(BigInteger.ONE).mod(P)
        val v = D.multiply(y2).add(BigInteger.ONE).mod(P)
        val xx = u.multiply(v.modInverse(P)).mod(P)
        var x = xx.modPow(P.add(BigInteger.valueOf(3)).divide(BigInteger.valueOf(8)), P)
        if (x.multiply(x).mod(P) != xx) {
            x = x.multiply(I).mod(P)
            if (x.multiply(x).mod(P) != xx) return null
        }
        if (x.testBit(0) != sign) x = P.subtract(x)
        return x
    }

    private fun pointAdd(p1: Point, p2: Point): Point {
        val x1x2 = p1.x.multiply(p2.x).mod(P)
        val y1y2 = p1.y.multiply(p2.y).mod(P)
        val dxxyy = D.multiply(x1x2).multiply(y1y2).mod(P)
        val x3 = p1.x.multiply(p2.y).add(p2.x.multiply(p1.y)).mod(P)
            .multiply(BigInteger.ONE.add(dxxyy).modInverse(P)).mod(P)
        val y3 = y1y2.add(x1x2).mod(P)
            .multiply(BigInteger.ONE.subtract(dxxyy).mod(P).modInverse(P)).mod(P)
        return Point(x3, y3)
    }

    private fun scalarMultiply(point: Point, scalar: BigInteger): Point {
        var result = IDENTITY
        var addend = point
        var s = scalar
        while (s.signum() > 0) {
            if (s.testBit(0)) result = pointAdd(result, addend)
            addend = pointAdd(addend, addend)
            s = s.shiftRight(1)
        }
        return result
    }

    private fun littleEndianToBigInt(bytes: ByteArray): BigInteger {
        val reversed = bytes.reversedArray()
        return BigInteger(1, reversed)
    }

    private fun sha512(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-512").digest(data)
}
