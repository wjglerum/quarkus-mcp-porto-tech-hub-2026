package nl.lunatech.porto.mcp.api;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import nl.lunatech.porto.mcp.security.StepUp;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.rest.client.ext.ClientHeadersFactory;

/**
 * What the token exchange loses, asserted by this hop. The token sent to conference-api is a new
 * one (aud=conference-api, azp=conference-mcp); these headers carry the step-up level and the
 * client the user actually signed in with. conference-api trusts them only on tokens issued to
 * conference-mcp.
 */
@ApplicationScoped
public class ExchangeHopHeaders implements ClientHeadersFactory {

    @Inject
    JsonWebToken jwt;

    @Inject
    StepUp stepUp;

    @Override
    public MultivaluedMap<String, String> update(MultivaluedMap<String, String> incoming,
            MultivaluedMap<String, String> outgoing) {
        MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
        headers.putSingle("X-Mcp-Acr", stepUp.verifiedAcr());
        Object azp = jwt.getClaim("azp");
        if (azp != null) {
            headers.putSingle("X-Mcp-Client", String.valueOf(azp));
        }
        return headers;
    }
}
