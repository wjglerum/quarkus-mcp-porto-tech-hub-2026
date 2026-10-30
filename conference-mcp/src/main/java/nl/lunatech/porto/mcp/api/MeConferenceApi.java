package nl.lunatech.porto.mcp.api;

import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkus.oidc.token.propagation.common.AccessToken;
import io.quarkus.rest.client.reactive.ClientExceptionMapper;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import nl.lunatech.porto.mcp.dto.AttendeeBookmarkDto;
import nl.lunatech.porto.mcp.dto.BookmarkDto;
import nl.lunatech.porto.mcp.dto.CancelSessionRequest;
import nl.lunatech.porto.mcp.dto.CreateBookmarkRequest;
import nl.lunatech.porto.mcp.dto.CreateRatingRequest;
import nl.lunatech.porto.mcp.dto.RatingDto;
import nl.lunatech.porto.mcp.dto.SessionDto;
import nl.lunatech.porto.mcp.dto.SessionFeedbackDto;
import nl.lunatech.porto.mcp.security.StepUp;
import org.eclipse.microprofile.rest.client.annotation.RegisterClientHeaders;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import java.util.List;

@RegisterRestClient(configKey = "conference-api-me")
// The user's token is not forwarded: quarkus.rest-client-oidc-token-propagation.exchange-token
// swaps it for one minted for conference-api (RFC 8693) before every call.
@AccessToken
@RegisterClientHeaders(ExchangeHopHeaders.class)
@Path("/api/v1")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public interface MeConferenceApi {

    @GET
    @Path("/me/agenda")
    List<BookmarkDto> myAgenda();

    @POST
    @Path("/me/agenda")
    BookmarkDto addBookmark(CreateBookmarkRequest req);

    @DELETE
    @Path("/me/agenda/{sessionId}")
    void removeBookmark(@PathParam("sessionId") Long sessionId);

    @GET
    @Path("/me/conflicts")
    List<SessionDto> conflicts();

    @POST
    @Path("/sessions/{id}/ratings")
    RatingDto rateSession(@PathParam("id") Long sessionId, CreateRatingRequest req);

    @GET
    @Path("/me/ratings")
    List<RatingDto> myRatings();

    @GET
    @Path("/me/sessions/feedback")
    List<SessionFeedbackDto> mySessionFeedback();

    @GET
    @Path("/sessions/{id}/attendees")
    List<AttendeeBookmarkDto> sessionAttendees(@PathParam("id") Long sessionId);

    @POST
    @Path("/sessions/{id}/cancel")
    SessionDto cancelSession(@PathParam("id") Long sessionId, CancelSessionRequest req);

    /**
     * Translates non-2xx backend responses into MCP {@link ToolCallException}s with stable,
     * LLM-friendly messages, so the tool methods can stay one-line delegations.
     */
    @ClientExceptionMapper
    static RuntimeException toToolException(Response response) {
        int status = response.getStatus();
        if (status < 400) return null;
        String body = response.readEntity(String.class);
        return switch (status) {
            // Only a step-up challenge (RFC 9470) should trigger the re-authenticate-with-MFA hint;
            // an expired or wrong-audience token is a plain authentication failure, not a step-up case.
            case 401 -> {
                String challenge = response.getHeaderString("WWW-Authenticate");
                if (challenge != null && challenge.contains("insufficient_user_authentication")) {
                    yield new ToolCallException("insufficient_user_authentication: backend requires step-up. "
                            + "Re-authenticate with acr_values=" + StepUp.MFA_ACR + " and retry.");
                }
                yield new ToolCallException("authentication_failed: the backend rejected the token"
                        + (challenge == null ? "" : " (" + challenge + ")")
                        + ". Sign in again to get a fresh token.");
            }
            case 422 -> new ToolCallException("rejected: "
                    + (body != null && body.contains("session_not_started") ? "session_not_started" : body));
            default -> new ToolCallException("backend_error: " + body);
        };
    }
}
