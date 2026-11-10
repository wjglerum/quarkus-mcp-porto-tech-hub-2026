package nl.lunatech.porto.mcp;

import io.quarkus.arc.Arc;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.oidc.server.OidcWiremockTestResource;
import nl.lunatech.porto.mcp.api.DelegatedTokenExchange;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TokenExchangeFilter is a REST client provider, not a bean, so it looks the exchange up
 * programmatically. Without {@code @Unremovable} Arc drops the bean at build time and every
 * conference-api call fails with a NullPointerException.
 */
@QuarkusTest
@QuarkusTestResource(OidcWiremockTestResource.class)
class TokenExchangeWiringTest {

    @Test
    void exchangeBeanSurvivesTheBuild() {
        assertTrue(Arc.container().instance(DelegatedTokenExchange.class).isAvailable());
    }
}
