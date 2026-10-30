package nl.lunatech.porto.api.seed;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import nl.lunatech.porto.api.clock.DemoClock;
import nl.lunatech.porto.api.domain.Attendee;
import nl.lunatech.porto.api.domain.AuditEvent;
import nl.lunatech.porto.api.domain.Bookmark;
import nl.lunatech.porto.api.domain.Rating;
import nl.lunatech.porto.api.domain.Session;
import nl.lunatech.porto.api.domain.Speaker;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

@ApplicationScoped
public class DemoDataSeeder {

    private static final List<String[]> FAKE_PEOPLE = List.of(
            new String[]{"attendee-alice",   "Alice Anderson"},
            new String[]{"attendee-bob",     "Bob Brown"},
            new String[]{"attendee-carol",   "Carol Clarke"},
            new String[]{"attendee-dave",    "Dave Davies"},
            new String[]{"attendee-erin",    "Erin Edwards"},
            new String[]{"attendee-frank",   "Frank Foster"},
            new String[]{"attendee-grace",   "Grace Green"},
            new String[]{"attendee-heidi",   "Heidi Hughes"},
            new String[]{"attendee-ivan",    "Ivan Irwin"},
            new String[]{"attendee-judy",    "Judy Jones"}
    );

    private static final List<String> NICE_COMMENTS = List.of(
            "Excellent talk, very practical.",
            "Loved the live demo.",
            "Wish the slides had more code.",
            "Best session of the day so far.",
            "Great use of caffeine.",
            "A bit fast for a beginner audience but solid material.",
            "Made a complex topic feel simple."
    );

    @Inject
    DemoClock clock;

    @Transactional
    public int seedIfEmpty() {
        if (Attendee.count() > 0) {
            Log.infof("Demo data: %d attendees already present, skipping", Attendee.count());
            return 0;
        }

        Speaker willemJan = Speaker.find("name", "Willem Jan Glerum").firstResult();

        Attendee speakerAttendee = new Attendee();
        speakerAttendee.subject = "willem.jan";
        speakerAttendee.displayName = "Willem Jan Glerum";
        speakerAttendee.speaker = willemJan;
        speakerAttendee.persist();

        List<Attendee> attendees = new ArrayList<>();
        attendees.add(speakerAttendee);
        for (String[] row : FAKE_PEOPLE) {
            Attendee a = new Attendee();
            a.subject = row[0];
            a.displayName = row[1];
            a.persist();
            attendees.add(a);
        }

        List<Session> sessions = Session.listAll();
        Random rnd = new Random(42);

        int bookmarks = 0;
        if (!sessions.isEmpty()) {
            // Fake attendees only: the speaker's agenda starts empty so demo 2 builds it live.
            for (Attendee a : attendees.subList(1, attendees.size())) {
                int n = 1 + rnd.nextInt(2);
                for (int i = 0; i < n; i++) {
                    Session s = sessions.get(rnd.nextInt(sessions.size()));
                    if (Bookmark.findOne(a.id, s.id) != null) continue;
                    Bookmark b = new Bookmark();
                    b.attendee = a;
                    b.session = s;
                    b.createdAt = OffsetDateTime.parse("2026-11-08T09:00:00+00:00").plusMinutes(bookmarks);
                    b.persist();
                    bookmarks++;
                }
            }
        }

        if (willemJan != null) {
            List<Session> mine = Session.list("speaker.id", willemJan.id);
            for (Session s : mine) {
                for (int i = 0; i < 3 && i + 1 < attendees.size(); i++) {
                    Attendee a = attendees.get(i + 1);
                    if (Bookmark.findOne(a.id, s.id) != null) continue;
                    Bookmark b = new Bookmark();
                    b.attendee = a;
                    b.session = s;
                    b.createdAt = OffsetDateTime.parse("2026-11-09T10:00:00+00:00").plusMinutes(bookmarks);
                    b.persist();
                    bookmarks++;
                }
            }
        }

        // Only sessions that have started by the demo clock can carry ratings, matching the
        // rating cutoff the API enforces for live ratings.
        OffsetDateTime now = clock.now();
        List<Session> started = sessions.stream().filter(s -> !s.startsAt.isAfter(now)).toList();
        int ratings = 0;
        if (!started.isEmpty()) {
            for (int i = 0; i < 50 && ratings < 25; i++) {
                Attendee a = attendees.get(1 + rnd.nextInt(attendees.size() - 1));
                Session s = started.get(rnd.nextInt(started.size()));
                if (Rating.findOne(a.id, s.id) != null) continue;
                Rating r = new Rating();
                r.attendee = a;
                r.session = s;
                r.stars = 2 + rnd.nextInt(4);
                r.comment = NICE_COMMENTS.get(rnd.nextInt(NICE_COMMENTS.size()));
                r.createdAt = now.minusMinutes(30).plusMinutes(ratings);
                r.persist();
                ratings++;
            }
        }

        int wjgRatings = 0;
        if (willemJan != null) {
            List<Session> mine = Session.list("speaker.id", willemJan.id);
            for (Session s : mine) {
                for (int i = 1; i < attendees.size() && wjgRatings < 5; i++) {
                    Attendee a = attendees.get(i);
                    if (a.subject.equals("willem.jan")) continue;
                    if (Rating.findOne(a.id, s.id) != null) continue;
                    Rating r = new Rating();
                    r.attendee = a;
                    r.session = s;
                    r.stars = 4 + (i % 2);
                    r.comment = NICE_COMMENTS.get(i % NICE_COMMENTS.size());
                    r.createdAt = now.minusMinutes(10).plusMinutes(wjgRatings);
                    r.persist();
                    wjgRatings++;
                }
                if (wjgRatings >= 5) break;
            }
        }

        int auditEvents = seedAuditTrail(sessions);

        Log.infof("Demo data: seeded %d attendees, %d bookmarks, %d ratings (%d on Willem Jan's sessions), %d audit events",
                attendees.size(), bookmarks, ratings + wjgRatings, wjgRatings, auditEvents);
        return attendees.size();
    }

    /**
     * Seed a representative attendee audit trail so the live dashboard shows real activity on
     * load. Real actions performed during the talk land on top of these.
     */
    private int seedAuditTrail(List<Session> sessions) {
        if (sessions.isEmpty()) return 0;
        Session talk = sessions.stream()
                .filter(s -> "Practical MCP Security in Action".equals(s.title))
                .findFirst().orElse(sessions.get(0));
        // A session that has not started yet, for the rejected-rating example.
        long laterSid = sessions.stream()
                .filter(s -> !s.startsAt.isBefore(talk.endsAt))
                .min(Comparator.comparing((Session s) -> s.startsAt).thenComparing(s -> s.id))
                .map(s -> s.id).orElse(talk.id);

        // Representative issuer for the seeded backdrop; live events carry the real dev-services
        // issuer (a random localhost port). Attendees browse through an MCP client (Claude,
        // CIMD-registered). The speaker's own step-up actions are left out on purpose: they
        // are the punchline of demo 3 and should land on the dashboard live.
        String iss = "https://auth.porto.demo/realms/porto";
        String aiClient = "claude-desktop via conference-mcp";
        Object[][] trail = {
                {"attendee-alice", "BOOKMARK_ADD", "session:" + talk.id, "1", "pwd", aiClient, "Practical MCP Security in Action"},
                {"attendee-bob", "BOOKMARK_ADD", "session:" + talk.id, "1", "pwd", aiClient, "Practical MCP Security in Action"},
                {"attendee-carol", "RATE_SESSION", "session:" + talk.id, "1", "pwd", aiClient, "stars=5 comment=great use of caffeine"},
                {"attendee-dave", "RATE_SESSION_REJECTED_NOT_STARTED", "session:" + laterSid, "1", "pwd", aiClient, "stars=4"},
                {"attendee-erin", "BOOKMARK_REMOVE", "session:" + talk.id, "1", "pwd", aiClient, null},
        };

        OffsetDateTime base = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(12);
        int n = 0;
        for (Object[] row : trail) {
            AuditEvent ev = new AuditEvent();
            ev.attendeeSubject = (String) row[0];
            ev.action = (String) row[1];
            ev.target = (String) row[2];
            ev.tokenAcr = (String) row[3];
            ev.tokenAmr = (String) row[4];
            ev.executedByClient = (String) row[5];
            ev.tokenIss = iss;
            ev.detail = (String) row[6];
            ev.createdAt = base.plusSeconds(n * 70L);
            ev.persist();
            n++;
        }
        return n;
    }
}
