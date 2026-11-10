package nl.lunatech.porto.mcp.api;

import io.quarkus.arc.Unremovable;
import io.quarkus.logging.Log;
import io.quarkus.oidc.client.OidcClients;
import io.quarkus.oidc.client.Tokens;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.time.Instant;
import java.util.Map;

/**
 * Trades the user's token for one minted for conference-api (RFC 8693), so the user's own token
 * never leaves this server.
 *
 * <p>When the user consented to {@code delegation:client:conference-mcp} at login, their token
 * carries a {@code may_act} claim naming this server. Then this is a <em>delegated</em> exchange:
 * this server also sends its own service-account token as the {@code actor_token}, and Keycloak
 * returns a token for the user with an {@code act} claim naming conference-mcp. The API sees both
 * who the data belongs to ({@code sub}) and who is acting ({@code act}).
 *
 * <p>Tokens without {@code may_act} (the Dev UI, clients that did not ask for delegation) get the
 * standard exchange instead: same user, same audience, no {@code act}. Keycloak refuses a standard
 * exchange for a token that does carry {@code may_act}, so the two paths cannot be mixed up.
 *
 * <p>Quarkus' token propagation filter cannot send an {@code actor_token} yet, which is why this
 * is done here and not with {@code quarkus.rest-client-oidc-token-propagation.exchange-token}.
 */
// Looked up programmatically from TokenExchangeFilter, so Arc must not remove it as unused.
@Unremovable
@ApplicationScoped
public class DelegatedTokenExchange {

    /** This server's client id: the actor named in may_act and act. */
    public static final String ACTOR = "conference-mcp";
    private static final String ACCESS_TOKEN_TYPE = "urn:ietf:params:oauth:token-type:access_token";
    /** Refresh the actor token this long before it expires. */
    private static final long ACTOR_TOKEN_SKEW_SECONDS = 30;

    @Inject
    OidcClients clients;

    private volatile Tokens actorTokens;

    /** Returns an access token for conference-api, for the user behind {@code jwt}. */
    public Uni<String> exchange(JsonWebToken jwt) {
        String subjectToken = jwt.getRawToken();
        if (!mayActForUs(jwt)) {
            Log.infof("Standard token exchange for %s (token has no may_act for %s)", jwt.getName(), ACTOR);
            return clients.getClient().getTokens(Map.of("subject_token", subjectToken))
                    .map(Tokens::getAccessToken);
        }
        Log.infof("Delegated token exchange for %s, actor %s", jwt.getName(), ACTOR);
        return actorToken().chain(actorToken -> clients.getClient().getTokens(Map.of(
                        "subject_token", subjectToken,
                        "actor_token", actorToken,
                        "actor_token_type", ACCESS_TOKEN_TYPE)))
                .map(Tokens::getAccessToken);
    }

    /** True when the user's token says this server may act for them. */
    static boolean mayActForUs(JsonWebToken jwt) {
        Object mayAct = jwt.getClaim("may_act");
        if (mayAct instanceof JsonObject json) {
            return json.get("client_id") instanceof JsonString id && ACTOR.equals(id.getString());
        }
        if (mayAct instanceof Map<?, ?> map) {
            return ACTOR.equals(map.get("client_id"));
        }
        return false;
    }

    /** This server's own token (client credentials), reused until shortly before it expires. */
    private Uni<String> actorToken() {
        Tokens current = actorTokens;
        if (current != null && !expiresSoon(current)) {
            return Uni.createFrom().item(current.getAccessToken());
        }
        return clients.getClient("actor").getTokens()
                .invoke(fresh -> actorTokens = fresh)
                .map(Tokens::getAccessToken);
    }

    private static boolean expiresSoon(Tokens tokens) {
        Long expiresAt = tokens.getAccessTokenExpiresAt();
        return expiresAt == null || Instant.now().getEpochSecond() + ACTOR_TOKEN_SKEW_SECONDS >= expiresAt;
    }
}
