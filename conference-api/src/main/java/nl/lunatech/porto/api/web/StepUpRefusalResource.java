package nl.lunatech.porto.api.web;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import nl.lunatech.porto.api.audit.AuditService;
import nl.lunatech.porto.api.dto.StepUpRefusalRequest;
import nl.lunatech.porto.api.security.StepUp;
import nl.lunatech.porto.api.security.Tokens;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.Map;

/**
 * conference-mcp refuses step-up tools on its own when the user's token lacks MFA (the token
 * exchange drops the acr, so only it can see that). It reports each refusal here so the audit log
 * shows it: during a prompt injection, that refusal is the first sign that something asked for
 * more than the user did.
 */
@Path("/api/v1/audit/refusals")
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("attendee")
public class StepUpRefusalResource {

    /** Only the step-up tools, mapped to the action names the dashboard colours as attempts. */
    private static final Map<String, String> ACTIONS = Map.of(
            "cancel_my_session", "CANCEL_SESSION_ATTEMPTED_WITHOUT_STEP_UP",
            "view_session_attendees", "VIEW_SESSION_ATTENDEES_ATTEMPTED_WITHOUT_STEP_UP");

    private static final int MAX_DETAIL = 500;

    @Inject
    JsonWebToken jwt;

    @Inject
    AuditService audit;

    @POST
    public void record(StepUpRefusalRequest req) {
        // Only the hop that saw the user's own token can know it lacked MFA.
        if (!StepUp.TRUSTED_HOP.equals(Tokens.azp(jwt))) {
            throw new ForbiddenException("only " + StepUp.TRUSTED_HOP + " reports step-up refusals");
        }
        String action = req == null ? null : ACTIONS.get(req.tool());
        if (action == null) {
            throw new BadRequestException("unknown step-up tool");
        }
        String detail = req.detail();
        if (detail != null && detail.length() > MAX_DETAIL) {
            detail = detail.substring(0, MAX_DETAIL);
        }
        audit.record(action, "tool:" + req.tool(), detail);
    }
}
