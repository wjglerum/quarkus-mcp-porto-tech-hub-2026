package nl.lunatech.porto.api.security;

import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.Map;

/** Reads step-up and provenance claims off an access token; amr is recorded for audit only. */
public final class Tokens {

    private Tokens() {}

    public static String acr(JsonWebToken jwt) {
        Object acr = jwt == null ? null : jwt.getClaim("acr");
        return acr == null ? null : String.valueOf(acr);
    }

    /** The authorized party (azp): the OAuth client the token was issued to. */
    public static String azp(JsonWebToken jwt) {
        Object azp = jwt == null ? null : jwt.getClaim("azp");
        return azp == null ? null : String.valueOf(azp);
    }

    /**
     * The client in the act claim (RFC 8693 delegation): who is acting for the subject. Only
     * present on a token from a delegated exchange, where Keycloak checked that the user
     * consented to that client acting for them.
     */
    public static String act(JsonWebToken jwt) {
        Object act = jwt == null ? null : jwt.getClaim("act");
        if (act instanceof JsonObject json) {
            return json.get("client_id") instanceof JsonString id ? id.getString() : null;
        }
        if (act instanceof Map<?, ?> map) {
            Object id = map.get("client_id");
            return id == null ? null : String.valueOf(id);
        }
        return null;
    }

    /** The client acting on this request: the act claim on a delegated token, else azp. */
    public static String actingClient(JsonWebToken jwt) {
        String act = act(jwt);
        return act != null ? act : azp(jwt);
    }

    /** The token issuer (iss): the authorization server that minted the token. */
    public static String iss(JsonWebToken jwt) {
        String iss = jwt == null ? null : jwt.getIssuer();
        return iss == null || iss.isBlank() ? null : iss;
    }

    /** The amr values comma-joined (no spaces), or null when absent. */
    public static String amr(JsonWebToken jwt) {
        Object amr = jwt == null ? null : jwt.getClaim("amr");
        if (amr instanceof Iterable<?> values) {
            StringBuilder sb = new StringBuilder();
            for (Object value : values) {
                if (sb.length() > 0) sb.append(',');
                sb.append(value);
            }
            return sb.length() == 0 ? null : sb.toString();
        }
        return amr == null ? null : String.valueOf(amr);
    }
}
