package com.example.cursor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CursorIdeasRepositoryTest {
    @Test fun normalizesBackendRootAndEndpoint() {
        val expected = "https://findit.example/api/ideas"
        assertEquals(expected, CursorIdeasRepository.normalizeEndpoint("https://findit.example/"))
        assertEquals(expected, CursorIdeasRepository.normalizeEndpoint("https://findit.example/api/ideas/"))
    }

    @Test fun rejectsInsecureOrAmbiguousConnections() {
        listOf("http://findit.example", "https://user:pass@findit.example", "https://findit.example?token=secret",
            "https://findit.example/#fragment", "https://findit.example/other-endpoint").forEach { endpoint ->
            assertThrows(IllegalArgumentException::class.java) { CursorIdeasRepository.normalizeEndpoint(endpoint) }
        }
    }

    @Test fun pendingRequestKeepsItsIdentityAndPayloadAcrossRestarts() {
        val idea = CodingIdea("6c6e74c2-47a2-4d7f-8454-31a905a150ae", "Improve progress",
            "Show the current import stage", "plan", "2.0 (3); Android 36")
        assertEquals(idea, CodingIdea.fromJson(idea.json()))
    }
}
