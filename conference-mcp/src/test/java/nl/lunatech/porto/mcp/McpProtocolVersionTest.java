package nl.lunatech.porto.mcp;

import io.quarkiverse.mcp.server.McpProtocolVersion;
import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkiverse.mcp.server.test.McpAssured.McpStreamableTestClient;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.oidc.server.OidcWiremockTestResource;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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
                .body("result.supportedVersions", hasItem(LATEST))
                .body("result._meta.'io.modelcontextprotocol/serverInfo'.name",
                        equalTo("Porto Tech Hub 2026 Conference Companion"));
    }

    @Test
    void statelessClientCallsToolsWithoutASession() {
        McpStreamableTestClient client = McpTestClients.authenticatedClient("willem.jan", Set.of("attendee", "speaker"))
                .setStateless()
                .build()
                .connect(init -> assertEquals("Porto Tech Hub 2026 Conference Companion", init.serverName()));

        assertNull(client.mcpSessionId());

        client.when()
                .toolsList(page -> assertNotNull(page.findByName("list_sessions")))
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
