package nl.lunatech.porto.mcp.api;

import io.quarkus.arc.Arc;
import io.quarkus.logging.Log;
import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.resteasy.reactive.client.spi.ResteasyReactiveClientRequestContext;
import org.jboss.resteasy.reactive.client.spi.ResteasyReactiveClientRequestFilter;

/**
 * Puts an exchanged token on every conference-api call (see {@link DelegatedTokenExchange}). The
 * user's own token is read on the calling thread, where the request scope is active; the exchange
 * itself runs without blocking.
 */
@Priority(Priorities.AUTHENTICATION)
public class TokenExchangeFilter implements ResteasyReactiveClientRequestFilter {

    @Override
    public void filter(ResteasyReactiveClientRequestContext requestContext) {
        JsonWebToken jwt = Arc.container().instance(JsonWebToken.class).get();
        if (jwt == null || jwt.getRawToken() == null) {
            requestContext.abortWith(Response.status(Response.Status.UNAUTHORIZED).build());
            return;
        }
        DelegatedTokenExchange exchange = Arc.container().instance(DelegatedTokenExchange.class).get();
        requestContext.suspend();
        exchange.exchange(jwt).subscribe().with(
                token -> {
                    requestContext.getHeaders().putSingle(HttpHeaders.AUTHORIZATION, "Bearer " + token);
                    requestContext.resume();
                },
                failure -> {
                    Log.warnf("Token exchange failed, not calling conference-api: %s", failure.getMessage());
                    requestContext.abortWith(Response.status(Response.Status.UNAUTHORIZED).build());
                    requestContext.resume();
                });
    }
}
