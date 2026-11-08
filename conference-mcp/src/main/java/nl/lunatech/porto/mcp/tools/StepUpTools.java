package nl.lunatech.porto.mcp.tools;

import io.quarkiverse.mcp.server.Tool;
import io.quarkus.logging.Log;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import nl.lunatech.porto.mcp.api.MeConferenceApi;
import nl.lunatech.porto.mcp.dto.AttendeeBookmarkDto;
import nl.lunatech.porto.mcp.dto.CancelSessionRequest;
import nl.lunatech.porto.mcp.dto.SessionDto;
import nl.lunatech.porto.mcp.dto.StepUpRefusalRequest;
import nl.lunatech.porto.mcp.security.StepUp;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.util.List;

@ApplicationScoped
@RolesAllowed("speaker")
public class StepUpTools {

    @Inject
    @RestClient
    MeConferenceApi me;

    @Inject
    StepUp stepUp;

    @Inject
    SessionResolver sessions;

    @Tool(name = "view_session_attendees",
            description = "List the display names of attendees who bookmarked one of the speaker's "
                    + "sessions. Exposes personal data, so it requires recent MFA-backed step-up. If "
                    + "the token does not satisfy the acr requirement, returns "
                    + "`insufficient_user_authentication`; the client must re-authenticate with "
                    + "`acr_values=urn:porto:mfa` and retry. Tell the user to sign in "
                    + "again with a one-time code.")
    public List<AttendeeBookmarkDto> viewSessionAttendees(
            @ToolArg(name = "session_id", description = SessionResolver.SESSION_ID, required = false) Long sessionId,
            @ToolArg(name = "session_query", description = SessionResolver.SESSION_QUERY, required = false) String sessionQuery) {
        requireStepUp("view_session_attendees", describe(sessionId, sessionQuery, null));
        return me.sessionAttendees(sessions.resolve(sessionId, sessionQuery));
    }

    @Tool(name = "cancel_my_session",
            description = "Mark one of the speaker's own sessions as cancelled, with a recorded "
                    + "reason. Set undo=true to restore a cancelled session instead; that records "
                    + "`CANCEL_SESSION_UNDONE`. Cancelling an already cancelled session, or restoring "
                    + "one that is not cancelled, fails rather than flipping it. Destructive, so it "
                    + "requires MFA-backed step-up. Pass session_query when you lack the id. Tell the "
                    + "user it is audited and visible on the live audit feed.")
    public SessionDto cancelMySession(
            @ToolArg(name = "session_id", description = SessionResolver.SESSION_ID, required = false) Long sessionId,
            @ToolArg(name = "session_query", description = SessionResolver.SESSION_QUERY, required = false) String sessionQuery,
            @ToolArg(name = "reason",
                    description = "Free-text reason recorded in the audit log.",
                    required = true) String reason,
            @ToolArg(name = "undo",
                    description = "true to restore a cancelled session; omit or false to cancel it.",
                    required = false) Boolean undo) {
        requireStepUp("cancel_my_session", describe(sessionId, sessionQuery, reason)
                + (Boolean.TRUE.equals(undo) ? " undo=true" : ""));
        return me.cancelSession(sessions.resolve(sessionId, sessionQuery),
                new CancelSessionRequest(reason, Boolean.TRUE.equals(undo)));
    }

    /**
     * Refuses without MFA, and first reports the refusal to conference-api so it lands in the
     * audit log. The report is best effort: if it fails, the refusal still stands.
     */
    private void requireStepUp(String tool, String detail) {
        if (stepUp.satisfied()) return;
        try {
            me.reportStepUpRefusal(new StepUpRefusalRequest(tool, detail));
        } catch (RuntimeException e) {
            Log.warnf("Could not audit the step-up refusal of %s: %s", tool, e.getMessage());
        }
        stepUp.require();
    }

    private static String describe(Long sessionId, String sessionQuery, String reason) {
        StringBuilder sb = new StringBuilder();
        if (sessionId != null) sb.append("session_id=").append(sessionId);
        if (sessionQuery != null) sb.append(sb.isEmpty() ? "" : " ").append("session_query=").append(sessionQuery);
        if (reason != null) sb.append(sb.isEmpty() ? "" : " ").append("reason=").append(reason);
        return sb.toString();
    }
}
