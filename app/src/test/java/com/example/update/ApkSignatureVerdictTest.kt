package com.example.update

import com.example.update.ApkInstaller.SignatureVerdict
import org.junit.Assert.assertEquals
import org.junit.Test

/** A downloaded APK whose signers the platform cannot read must not be reported as "different key". */
class ApkSignatureVerdictTest {
    private val ci = byteArrayOf(1, 2, 3)
    private val other = byteArrayOf(9, 9, 9)

    @Test
    fun sameKeyMatches() =
        assertEquals(SignatureVerdict.MATCH, ApkInstaller.signatureVerdict(listOf(ci), listOf(byteArrayOf(1, 2, 3))))

    @Test
    fun differentKeyIsMismatch() =
        assertEquals(SignatureVerdict.MISMATCH, ApkInstaller.signatureVerdict(listOf(ci), listOf(other)))

    @Test
    fun unreadableArchiveSignersAreUnknownNotMismatch() =
        assertEquals(SignatureVerdict.UNKNOWN, ApkInstaller.signatureVerdict(listOf(ci), emptyList()))

    @Test
    fun unreadableInstalledSignersAreUnknown() =
        assertEquals(SignatureVerdict.UNKNOWN, ApkInstaller.signatureVerdict(emptyList(), listOf(ci)))

    @Test
    fun rotatedHistoryStillMatches() =
        assertEquals(SignatureVerdict.MATCH, ApkInstaller.signatureVerdict(listOf(other, ci), listOf(ci)))
}
