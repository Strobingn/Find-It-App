package com.example.gps

import com.example.gps.averaging.PreciseFixAverager
import com.example.gps.crs.GeodeticFrame
import com.example.gps.nmea.NmeaParser
import com.example.gps.ntrip.NtripAuthException
import com.example.gps.ntrip.NtripClient
import com.example.gps.ntrip.NtripConfig
import com.example.gps.ntrip.NtripPresets
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.util.Random
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NmeaParserTest {
    @Test fun parsesRtkFixedGgaAndRejectsBadChecksum() {
        val line = "\$GNGGA,123519.00,4807.0381234,N,01131.0004321,W,4,18,0.6,545.4,M,46.9,M,1.0,0000"
        var sum = 0
        for (c in line.substring(1)) sum = sum xor c.code
        val full = line + "*" + "%02X".format(sum)
        val gga = NmeaParser.parseGga(full)!!
        assertEquals(4, gga.quality)
        assertEquals(48 + 7.0381234 / 60, gga.latitude, 1e-10)
        assertEquals(-(11 + 31.0004321 / 60), gga.longitude, 1e-10)
        assertEquals(18, gga.satellites)
        assertNull(NmeaParser.parseGga(full.dropLast(2) + "00"))
    }

    @Test fun gstGivesHorizontalSigma() {
        val body = "GPGST,172814.0,0.006,0.023,0.020,273.6,0.012,0.009,0.034"
        var sum = 0
        for (c in body) sum = sum xor c.code
        val gst = NmeaParser.parseGst("$" + body + "*" + "%02X".format(sum))!!
        assertEquals(kotlin.math.hypot(0.012, 0.009), gst.horizontalSigmaMeters, 1e-12)
    }

    @Test fun builtGgaRoundTrips() {
        val gga = NmeaParser.buildGga(42.0085646, -74.7826258, 512.3, 1_760_000_000_000L)
        val parsed = NmeaParser.parseGga(gga)!!
        assertEquals(42.0085646, parsed.latitude, 1e-9)
        assertEquals(-74.7826258, parsed.longitude, 1e-9)
    }
}

class AccuracyLabelTest {
    private fun fix(q: FixQuality, acc: Float) =
        GnssFix(0.0, 0.0, null, acc, FixSource.PHONE, q, GeodeticFrame.ITRF, 0L)

    @Test fun phoneAccuracyIsAlwaysAnEstimate() {
        assertEquals("±0.8 m est.", fix(FixQuality.AUTONOMOUS, 0.8f).accuracyLabel())
        assertFalse(fix(FixQuality.AUTONOMOUS, 0.4f).accuracyLabel().contains("≤"))
    }

    @Test fun rtkLabelOnlyWhenReceiverReportsIt() {
        assertEquals("RTK fixed ±0.02 m", fix(FixQuality.RTK_FIXED, 0.02f).accuracyLabel())
        assertEquals("RTK float ±0.3 m", fix(FixQuality.RTK_FLOAT, 0.3f).accuracyLabel())
        assertFalse(fix(FixQuality.ESTIMATED, 0.02f).accuracyLabel().contains("RTK"))
    }
}

class PreciseFixAveragerTest {
    private fun at(t: Long, dE: Double, dN: Double, acc: Float): GnssFix {
        val lat = 42.0 + dN / 111_132.954
        val lon = -74.7 + dE / (111_319.49 * kotlin.math.cos(Math.toRadians(42.0)))
        return GnssFix(lat, lon, 100.0, acc, FixSource.PHONE, FixQuality.AUTONOMOUS, GeodeticFrame.ITRF, t)
    }

    @Test fun rejectsOutliersAndConvergesNearTruth() {
        val rnd = Random(7)
        val avg = PreciseFixAverager()
        var p: PreciseFixAverager.Progress? = null
        for (i in 0..60) {
            val outlier = i % 15 == 7
            val e = if (outlier) 25.0 else rnd.nextGaussian() * 1.5
            val n = if (outlier) -30.0 else rnd.nextGaussian() * 1.5
            p = avg.add(at(i * 1000L, e, n, if (outlier) 3f else 2f))
            if (p.done) break
        }
        val done = p!!
        assertTrue(done.done)
        assertTrue("rejected ${done.rejectedFixes}", done.rejectedFixes >= 2)
        val est = done.estimate!!
        val (e, n) = (est.longitude + 74.7) * 111_319.49 * kotlin.math.cos(Math.toRadians(42.0)) to (est.latitude - 42.0) * 111_132.954
        assertTrue("error ${kotlin.math.hypot(e, n)}", kotlin.math.hypot(e, n) < 1.0)
        assertEquals(FixQuality.ESTIMATED, est.quality)
        assertTrue(est.accuracyLabel().endsWith("est."))
    }

    @Test fun correlatedPhoneErrorsDoNotClaimCentimetres() {
        val avg = PreciseFixAverager()
        var p: PreciseFixAverager.Progress? = null
        for (i in 0..60) p = avg.add(at(i * 1000L, 0.0, 0.0, 3f))
        // 61 fixes of ±3 m over 60 s: ≈ 12 independent samples → ±0.87 m, never ±0.38 (√61).
        val sigma = p!!.estimate!!.horizontalAccuracyMeters
        assertTrue("sigma $sigma", sigma in 0.8f..0.95f)
    }

    @Test fun neverFinishesBeforeThirtySeconds() {
        val avg = PreciseFixAverager()
        var p: PreciseFixAverager.Progress? = null
        for (i in 0..20) p = avg.add(at(i * 1000L, 0.0, 0.0, 0.2f))
        assertFalse(p!!.done)
    }
}

class NtripClientTest {
    private fun fakeCaster(response: (String) -> ByteArray, onClientBytes: (String) -> Unit = {}): Pair<ServerSocket, Thread> {
        val server = ServerSocket(0)
        val t = thread(isDaemon = true) {
            server.accept().use { s ->
                val input = s.getInputStream()
                val head = StringBuilder()
                while (!head.endsWith("\r\n\r\n")) { val c = input.read(); if (c < 0) break; head.append(c.toChar()) }
                s.getOutputStream().write(response(head.toString())); s.getOutputStream().flush()
                s.soTimeout = 3_000
                val extra = ByteArrayOutputStream()
                runCatching { val buf = ByteArray(512); val n = input.read(buf); if (n > 0) extra.write(buf, 0, n) }
                onClientBytes(extra.toString(Charsets.US_ASCII.name()))
                Thread.sleep(200)
            }
        }
        return server to t
    }

    @Test fun streamsChunkedRtcmV2AndSendsGga() {
        val rtcm = ByteArray(300) { (it * 7).toByte() }
        var request = ""
        var clientSent = ""
        val (server, t) = fakeCaster({ req ->
            request = req
            val out = ByteArrayOutputStream()
            out.write("HTTP/1.1 200 OK\r\nNtrip-Version: Ntrip/2.0\r\nContent-Type: gnss/data\r\nTransfer-Encoding: chunked\r\n\r\n".toByteArray())
            out.write("%x\r\n".format(100).toByteArray()); out.write(rtcm, 0, 100); out.write("\r\n".toByteArray())
            out.write("%x\r\n".format(200).toByteArray()); out.write(rtcm, 100, 200); out.write("\r\n0\r\n\r\n".toByteArray())
            out.toByteArray()
        }, { clientSent = it })
        val received = ByteArrayOutputStream()
        val client = NtripClient(NtripConfig("127.0.0.1", server.localPort, "NEAR_RTCM3", "user", "pa:ss"))
        runCatching {
            client.stream(gga = { "\$GPGGA,TEST*00" }, onRtcm = { b, n -> received.write(b, 0, n) }, shouldContinue = { received.size() < 300 })
        }
        t.join(5_000); server.close()
        assertArrayEquals(rtcm, received.toByteArray())
        assertTrue(request.startsWith("GET /NEAR_RTCM3 HTTP/1.1"))
        assertTrue(request.contains("Ntrip-Version: Ntrip/2.0"))
        assertTrue(request.contains("Authorization: Basic " + java.util.Base64.getEncoder().encodeToString("user:pa:ss".toByteArray())))
        assertTrue("GGA uploaded: $clientSent", clientSent.contains("\$GPGGA,TEST*00"))
    }

    @Test fun ntripV1IcyResponseStreams() {
        val (server, t) = fakeCaster({ "ICY 200 OK\r\n".toByteArray() + ByteArray(64) { 0xD3.toByte() } })
        val received = ByteArrayOutputStream()
        runCatching {
            NtripClient(NtripConfig("127.0.0.1", server.localPort, "M", "u", "p", sendGga = false))
                .stream(gga = { null }, onRtcm = { b, n -> received.write(b, 0, n) }, shouldContinue = { received.size() < 64 })
        }
        t.join(5_000); server.close()
        assertEquals(64, received.size())
    }

    @Test fun badLoginIsReportedAsAuthError() {
        val (server, t) = fakeCaster({ "HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\n\r\n".toByteArray() })
        val error = runCatching {
            NtripClient(NtripConfig("127.0.0.1", server.localPort, "M", "u", "wrong"))
                .stream(gga = { null }, onRtcm = { _, _ -> }, shouldContinue = { true })
        }.exceptionOrNull()
        t.join(5_000); server.close()
        assertTrue("got $error", error is NtripAuthException)
    }

    @Test fun parsesSourceTable() {
        val table = "SOURCETABLE 200 OK\r\nSTR;NEAR_RTCM3;Nearest;RTCM 3.2;1004(1);2;GPS+GLO;NYSNet;USA;42.65;-73.75;1;0;sNTRIP;none;B;N;9600;\r\nENDSOURCETABLE\r\n"
        val mounts = NtripClient.parseSourceTable(table)
        assertEquals(1, mounts.size)
        assertEquals("NEAR_RTCM3", mounts[0].name)
        assertTrue(mounts[0].requiresGga)
    }

    @Test fun nysnetPresetHasNoBundledCredentials() {
        val p = NtripPresets.NYSNET
        assertEquals("rtn.dot.ny.gov", p.host)
        assertTrue(p.correctedFrameIsNad83)
        assertNotNull(p.registrationUrl)
        assertFalse(GpsSettings().ntripConfig().isComplete)
    }
}

class BasicAuthBase64Test {
    @org.junit.Test fun matchesJdk() {
        for (s in listOf("", "a", "ab", "abc", "user:pässwörd!", "nysnet_user:p@ss:w0rd")) {
            val b = s.toByteArray(Charsets.UTF_8)
            org.junit.Assert.assertEquals(java.util.Base64.getEncoder().encodeToString(b), com.example.gps.ntrip.basicAuthBase64(b))
        }
    }
}
