package com.example.feedreader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LlmServicePolicyTest {
    private val base = "http://reader.example:8000"

    @Test fun permitsOnlyApiAndHealthOnTheSavedComputer() {
        assertEquals("/health", LlmServicePolicy.request(base, "$base/health").encodedPath)
        assertEquals("limit=20", LlmServicePolicy.request(base, "$base/api/v1/feed?limit=20").query)
    }

    @Test fun refusesCrossOriginCredentialAndPathEscapes() {
        listOf(
            "http://other.example:8000/api/v1/feed", "https://reader.example:8000/api/v1/feed",
            "http://reader.example:9000/api/v1/feed", "$base/admin", "$base/api/v1/../../admin",
            "http://user:password@reader.example:8000/api/v1/feed", "$base/health#fragment",
            "file:///api/v1/feed",
        ).forEach { target -> assertThrows(RuntimeException::class.java) { LlmServicePolicy.request(base, target) } }
    }

    @Test fun neverTreatsTheBundledApplicationOrAUrlWithCredentialsAsAComputerService() {
        listOf("https://appassets.androidplatform.net", "$base/subpath", "$base?key=test", "http://user:password@reader.example:8000")
            .forEach { target -> assertThrows(RuntimeException::class.java) { LlmServicePolicy.base(target) } }
    }
}
