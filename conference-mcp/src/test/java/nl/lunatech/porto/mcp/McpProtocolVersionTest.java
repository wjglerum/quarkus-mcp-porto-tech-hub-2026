package nl.lunatech.porto.mcp;

import io.quarkiverse.mcp.server.McpProtocolVersion;
import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkiverse.mcp.server.test.McpAssured.McpStreamableTestClient;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.oidc.server.OidcWiremockTestResource;
import io.vertx.core.json.JsonObject;
import nl.lunatech.porto.mcp.api.PublicConferenceApi;
import nl.lunatech.porto.mcp.dto.SessionDto;
import nl.lunatech.porto.mcp.dto.SpeakerRef;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Pins the server to the latest MCP spec (2026-07-28): a stateless client skips the
 * initialize handshake, discovers the server with {@code server/discover}, and then calls
 * tools without an {@code Mcp-Session-Id}. The bearer token still gates every request.
 * Session-based clients on the previous spec keep working on the same endpoint.
 */
@QuarkusTest
@QuarkusTestResource(OidcWiremockTestResource.class)
class McpProtocolVersionTest {

    private static final String LATEST = McpProtocolVersion.V_2026_07_28.version();

    @InjectMock
    @RestClient
    PublicConferenceApi api;

    @Test
    void serverDiscoverAdvertisesTheLatestSpec() {
        String token = OidcWiremockTestResource.getAccessToken("willem.jan", Set.of("attendee"));
        JsonObject discover = new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", McpAssured.SERVER_DISCOVER);
        McpAssured.injectStatelessMeta(discover);

        given().auth().oauth2(token)
                .contentType("application/json")
                .accept("application/json, text/event-stream")
                .header("MCP-Protocol-Version", LATEST)
                .header("Mcp-Method", "server/discover")
                .body(discover.encode())
                .when().post("/mcp")
                .then().statusCode(200)
                .header("Mcp-Session-Id", nullValue())
                // The server lists its versions newest first, so this pins 2026-07-28 as the latest.
                .body("result.supportedVersions[0]", equalTo(LATEST))
                .body("result._meta.'io.modelcontextprotocol/serverInfo'.name",
                        equalTo("Porto Tech Hub 2026 Conference Companion"));
    }

    @Test
    void statelessClientCallsToolsWithoutASession() {
        when(api.listSessions(null, null, "MCP")).thenReturn(List.of(new SessionDto(1L,
                "Practical MCP Security in Action", "OAuth 2.1 done right.", "Sofico track",
                OffsetDateTime.parse("2026-11-10T11:10:00+00:00"), OffsetDateTime.parse("2026-11-10T12:00:00+00:00"),
                false, null, new SpeakerRef(1L, "Willem Jan Glerum"))));

        McpStreamableTestClient client = McpTestClients.authenticatedClient("willem.jan", Set.of("attendee"))
                .setStateless()
                .build()
                .connect(init -> assertEquals("Porto Tech Hub 2026 Conference Companion", init.serverName()));

        assertNull(client.mcpSessionId());

        client.when()
                .toolsCall("list_sessions", Map.of("query", "MCP"), response -> {
                    assertFalse(response.isError());
                    assertTrue(response.firstContent().asText().text().contains("Practical MCP Security in Action"));
                })
                .thenAssertResults();

        client.disconnect();
    }

    @Test
    void stepUpStillAppliesPerRequestInStatelessMode() {
        // No session to remember a login in: the bearer token on this one request lacks the MFA
        // acr, so the step-up tool refuses before it touches the API.
        McpStreamableTestClient client = McpTestClients.authenticatedClient("willem.jan", Set.of("attendee", "speaker"))
                .setStateless()
                .build()
                .connect();

        client.when()
                .toolsCall("view_session_attendees", Map.of("session_id", 1), response -> {
                    assertTrue(response.isError());
                    assertTrue(response.firstContent().asText().text().contains("insufficient_user_authentication"));
                })
                .thenAssertResults();

        client.disconnect();
    }

    @Test
    void sessionBasedClientsOnThePreviousSpecStillConnect() {
        McpStreamableTestClient client = McpTestClients.authenticatedClient("willem.jan", Set.of("attendee"))
                .setProtocolVersion(McpProtocolVersion.V_2025_11_25)
                .build()
                .connect(init -> assertEquals(McpProtocolVersion.V_2025_11_25.version(), init.protocolVersion()));

        assertNotNull(client.mcpSessionId());
        client.disconnect();
    }
}
