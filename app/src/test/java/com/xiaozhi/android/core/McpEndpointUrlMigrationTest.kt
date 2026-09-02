package com.xiaozhi.android.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64

class McpEndpointUrlMigrationTest {

    @Test
    fun replacesObsoleteOfficialEndpointWithoutEndpointId() {
        val defaultUrl = "wss://api.xiaozhi.me/mcp/?token=current"
        val obsoleteUrl = officialUrl(
            payload = """
                {"agentId":2262431,"endpoint":"agent_226431","purpose":"mcp-endpoint"}
            """.trimIndent()
        )

        assertEquals(defaultUrl, McpEndpointUrlMigration.resolve(obsoleteUrl, defaultUrl))
    }

    @Test
    fun keepsOfficialEndpointWithEndpointId() {
        val defaultUrl = "wss://api.xiaozhi.me/mcp/?token=current"
        val currentUrl = officialUrl(
            payload = """
                {"agentId":2262431,"endpointId":"agent_2262431","purpose":"mcp-endpoint"}
            """.trimIndent()
        )

        assertEquals(currentUrl, McpEndpointUrlMigration.resolve(currentUrl, defaultUrl))
    }

    @Test
    fun keepsCustomEndpointAndUsesDefaultWhenSavedValueIsMissing() {
        val defaultUrl = "wss://api.xiaozhi.me/mcp/?token=current"
        val customUrl = "wss://example.com/mcp/?token=custom"

        assertEquals(customUrl, McpEndpointUrlMigration.resolve(customUrl, defaultUrl))
        assertEquals(defaultUrl, McpEndpointUrlMigration.resolve(null, defaultUrl))
        assertEquals(defaultUrl, McpEndpointUrlMigration.resolve(" ", defaultUrl))
    }

    private fun officialUrl(payload: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("""{"alg":"ES256","typ":"JWT"}""".toByteArray())
        val encodedPayload = encoder.encodeToString(payload.toByteArray())
        return "wss://api.xiaozhi.me/mcp/?token=$header.$encodedPayload.signature"
    }
}
