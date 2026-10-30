package nl.lunatech.porto.mcp.tools;

import io.quarkiverse.mcp.server.ToolCallException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import nl.lunatech.porto.mcp.api.PublicConferenceApi;
import nl.lunatech.porto.mcp.dto.SessionDto;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Resolves a session reference to a numeric id so the session-acting tools can be
 * called with either an explicit {@code session_id} or a human {@code session_query}
 * (a few words of the title). This lets the model act on "the MCP Security talk" in a
 * single tool call instead of having to look the id up first.
 */
@ApplicationScoped
public class SessionResolver {

    public static final String SESSION_ID =
            "Numeric session id. Provide this if you already know it.";
    public static final String SESSION_QUERY =
            "A few distinctive words from the talk title (e.g. 'MCP Security'), used to find the "
                    + "session when the numeric id is unknown.";

    // Words a model tends to add around the title ("my MCP Security talk") that never appear in
    // titles; the API does a substring match, so they would make the query miss.
    private static final Set<String> FILLER = Set.of(
            "the", "a", "an", "my", "talk", "session", "presentation", "keynote", "deep", "dive");

    @Inject
    @RestClient
    PublicConferenceApi api;

    public long resolve(Long sessionId, String sessionQuery) {
        if (sessionId != null) {
            return sessionId;
        }
        if (sessionQuery == null || sessionQuery.isBlank()) {
            throw new ToolCallException("invalid_argument: provide either session_id or session_query");
        }
        return api.listSessions(null, null, stripFiller(sessionQuery)).stream()
                .findFirst()
                .map(SessionDto::id)
                .orElseThrow(() -> new ToolCallException(
                        "not_found: no session matches \"" + sessionQuery + "\". "
                                + "Try fewer, more distinctive words from the title."));
    }

    static String stripFiller(String query) {
        String stripped = Arrays.stream(query.trim().split("\\s+"))
                .filter(w -> !FILLER.contains(w.toLowerCase(Locale.ROOT)))
                .collect(Collectors.joining(" "));
        return stripped.isEmpty() ? query.trim() : stripped;
    }
}
