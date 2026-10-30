package nl.lunatech.porto.mcp.security;

import io.quarkiverse.mcp.server.ToolCallException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

@ApplicationScoped
public class StepUp {

    public static final String MFA_ACR = "urn:porto:mfa";

    @Inject
    JsonWebToken jwt;

    public void require() {
        if (!satisfied()) {
            throw new ToolCallException(
                    "insufficient_user_authentication: this tool requires step-up MFA. "
                            + "Re-authenticate with acr_values=" + MFA_ACR + " and retry.");
        }
    }

    /**
     * The acr this server verified on the user's own token, passed on to conference-api: the
     * token exchange drops it from the token the API receives.
     */
    public String verifiedAcr() {
        return satisfied() ? MFA_ACR : String.valueOf((Object) jwt.getClaim("acr"));
    }

    private boolean satisfied() {
        String acr = String.valueOf((Object) jwt.getClaim("acr"));
        // "2" covers a realm without the acr.loa.map alias
        return MFA_ACR.equals(acr) || "2".equals(acr);
    }
}
