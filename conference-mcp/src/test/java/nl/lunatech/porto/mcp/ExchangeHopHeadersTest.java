package nl.lunatech.porto.mcp;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.oidc.server.OidcWiremockTestResource;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import nl.lunatech.porto.mcp.api.ExchangeHopHeaders;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The token exchange drops the step-up acr and the original client from the token conference-api
 * receives; these headers carry what this hop verified on the user's own token.
 */
@QuarkusTest
@QuarkusTestResource(OidcWiremockTestResource.class)
class ExchangeHopHeadersTest {

    @Inject
    ExchangeHopHeaders headers;

    private MultivaluedMap<String, String> outgoing() {
        return headers.update(new MultivaluedHashMap<>(), new MultivaluedHashMap<>());
    }

    @Test
    @TestSecurity(user = "willem.jan", roles = {"attendee", "speaker"})
    @OidcSecurity(claims = {@Claim(key = "acr", value = "urn:porto:mfa"), @Claim(key = "azp", value = "conference-chat")})
    void assertsTheVerifiedStepUpAndTheOriginalClient() {
        MultivaluedMap<String, String> out = outgoing();
        assertEquals("urn:porto:mfa", out.getFirst("X-Mcp-Acr"));
        assertEquals("conference-chat", out.getFirst("X-Mcp-Client"));
    }

    @Test
    @TestSecurity(user = "willem.jan", roles = {"attendee", "speaker"})
    @OidcSecurity(claims = {@Claim(key = "acr", value = "2"), @Claim(key = "azp", value = "conference-chat")})
    void normalisesTheNumericLevelOfAuthentication() {
        assertEquals("urn:porto:mfa", outgoing().getFirst("X-Mcp-Acr"));
    }

    @Test
    @TestSecurity(user = "attendee", roles = {"attendee"})
    @OidcSecurity(claims = {@Claim(key = "acr", value = "1"), @Claim(key = "azp", value = "claude-desktop")})
    void passesAPlainLoginOnAsIs() {
        MultivaluedMap<String, String> out = outgoing();
        assertEquals("1", out.getFirst("X-Mcp-Acr"));
        assertEquals("claude-desktop", out.getFirst("X-Mcp-Client"));
    }
}
