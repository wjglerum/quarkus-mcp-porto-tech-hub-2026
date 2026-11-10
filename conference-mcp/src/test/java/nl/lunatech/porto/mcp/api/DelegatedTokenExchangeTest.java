package nl.lunatech.porto.mcp.api;

import jakarta.json.Json;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Picks the exchange: delegated only when the user's token says conference-mcp may act for them.
 * Keycloak refuses a standard exchange for a token with may_act, and a delegated one without it.
 */
class DelegatedTokenExchangeTest {

    @Test
    void delegatesWhenMayActNamesThisServer() {
        assertTrue(DelegatedTokenExchange.mayActForUs(token(Json.createObjectBuilder()
                .add("sub", "service-account-id")
                .add("client_id", "conference-mcp")
                .build())));
    }

    @Test
    void doesNotDelegateForAnotherActor() {
        assertFalse(DelegatedTokenExchange.mayActForUs(token(Json.createObjectBuilder()
                .add("sub", "service-account-id")
                .add("client_id", "some-other-agent")
                .build())));
    }

    @Test
    void usesTheStandardExchangeWithoutMayAct() {
        assertFalse(DelegatedTokenExchange.mayActForUs(token(null)));
    }

    @Test
    void acceptsMayActAsAPlainMap() {
        assertTrue(DelegatedTokenExchange.mayActForUs(token(Map.of("client_id", "conference-mcp"))));
    }

    private static JsonWebToken token(Object mayAct) {
        return new JsonWebToken() {
            @Override
            public String getName() {
                return "willem.jan";
            }

            @Override
            public Set<String> getClaimNames() {
                return mayAct == null ? Set.of() : Set.of("may_act");
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> T getClaim(String claimName) {
                return "may_act".equals(claimName) ? (T) mayAct : null;
            }
        };
    }
}
