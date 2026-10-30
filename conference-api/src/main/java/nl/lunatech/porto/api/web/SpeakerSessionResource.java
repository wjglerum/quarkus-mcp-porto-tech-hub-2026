package nl.lunatech.porto.api.web;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import nl.lunatech.porto.api.audit.AuditService;
import nl.lunatech.porto.api.domain.Attendee;
import nl.lunatech.porto.api.domain.Bookmark;
import nl.lunatech.porto.api.domain.Session;
import nl.lunatech.porto.api.dto.AttendeeBookmarkDto;
import nl.lunatech.porto.api.dto.CancelSessionRequest;
import nl.lunatech.porto.api.dto.SessionDto;
import nl.lunatech.porto.api.security.StepUp;

import java.util.List;

@Path("/api/v1/sessions/{id}")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("speaker")
public class SpeakerSessionResource {

    // Not @AuthenticationContext: the token arrives through conference-mcp's token exchange,
    // which drops the acr, so StepUp also accepts the acr that hop verified.
    @Inject
    StepUp stepUp;

    @Inject
    AttendeeService attendees;

    @Inject
    AuditService audit;

    @GET
    @Path("/attendees")
    @Transactional
    public List<AttendeeBookmarkDto> attendees(@PathParam("id") Long id) {
        stepUp.require();
        Session session = requireOwnedSession(id);
        List<AttendeeBookmarkDto> list = Bookmark.<Bookmark>list("session.id = ?1 order by createdAt asc", session.id)
                .stream()
                .map(AttendeeBookmarkDto::of)
                .toList();
        audit.record("VIEW_SESSION_ATTENDEES", "session:" + id, list.size() + " attendees disclosed");
        return list;
    }

    @POST
    @Path("/cancel")
    @Transactional
    public SessionDto cancel(@PathParam("id") Long id, CancelSessionRequest req) {
        stepUp.require();
        if (req == null || req.reason() == null || req.reason().isBlank()) {
            throw new WebApplicationException("reason is required", 400);
        }
        Session session = requireOwnedSession(id);
        boolean wasCancelled = session.cancelled;
        session.cancelled = !wasCancelled;
        session.cancellationReason = wasCancelled ? null : req.reason();
        session.persist();
        audit.record(
                wasCancelled ? "CANCEL_SESSION_UNDONE" : "CANCEL_SESSION",
                "session:" + id,
                "reason=" + req.reason());
        return SessionDto.of(session);
    }

    private Session requireOwnedSession(Long id) {
        Session s = Session.findById(id);
        if (s == null) throw new NotFoundException("session " + id);
        Attendee me = attendees.currentAttendee();
        if (me.speaker == null || s.speaker == null || !s.speaker.id.equals(me.speaker.id)) {
            audit.record("CANCEL_SESSION_ATTEMPTED", "session:" + id, "not a speaker on session");
            throw new ForbiddenException("you are not a speaker on this session");
        }
        return s;
    }
}
