package nl.lunatech.porto.api.security;

import io.vertx.core.http.HttpServerRequest;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * Step-up (RFC 9470) for the speaker endpoints, across a token exchange hop.
 *
 * <p>conference-mcp does not forward the user's token: it exchanges it (RFC 8693) for one with
 * {@code aud=conference-api} and {@code azp=conference-mcp}. Keycloak's standard token exchange
 * does not carry the step-up {@code acr} into that new token, so the hop that saw the original
 * token has to assert it. Only a token issued to {@link #TRUSTED_HOP} can make that assertion,
 * and only conference-mcp holds that client's secret; for any other caller the token's own
 * {@code acr} is all that counts.
 */
@RequestScoped
public class StepUp {

    public static final String MFA_ACR = "urn:porto:mfa";
    /** The only client allowed to assert the acr and client of the token it exchanged. */
    public static final String TRUSTED_HOP = "conference-mcp";
    public static final String ACR_HEADER = "X-Mcp-Acr";
    public static final String CLIENT_HEADER = "X-Mcp-Client";

    @Inject
    JsonWebToken jwt;

    @Inject
    HttpServerRequest request;

    /** The assurance level this request carries: asserted by the trusted hop, else the token's own. */
    public String acr() {
        String asserted = fromTrustedHop(ACR_HEADER);
        return asserted != null ? asserted : Tokens.acr(jwt);
    }

    /** The client that acted: "conference-chat via conference-mcp" across the hop, else the token's azp. */
    public String executedByClient() {
        String origin = fromTrustedHop(CLIENT_HEADER);
        return origin != null ? origin + " via " + TRUSTED_HOP : Tokens.azp(jwt);
    }

    /** Throws the RFC 9470 challenge (401 + WWW-Authenticate) unless the request carries MFA. */
    public void require() {
        if (!MFA_ACR.equals(acr())) {
            throw new WebApplicationException(Response.status(Response.Status.UNAUTHORIZED)
                    .header("WWW-Authenticate", "Bearer error=\"insufficient_user_authentication\", "
                            + "error_description=\"A different authentication level is required\", "
                            + "acr_values=\"" + MFA_ACR + "\"")
                    .build());
        }
    }

    private String fromTrustedHop(String header) {
        if (!TRUSTED_HOP.equals(Tokens.azp(jwt))) return null;
        String value = request.getHeader(header);
        return value == null || value.isBlank() ? null : value;
    }
}
