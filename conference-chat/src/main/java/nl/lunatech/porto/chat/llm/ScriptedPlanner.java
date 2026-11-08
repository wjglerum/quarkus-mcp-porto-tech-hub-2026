package nl.lunatech.porto.chat.llm;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Stage fallback for when the LLM is unreachable: maps the rehearsed demo prompts to the tool
 * call the model would have made. Only fixed phrases match, so it never pretends to understand
 * anything else. The MCP call itself, and so all of the security, is exactly the same.
 */
@ApplicationScoped
public class ScriptedPlanner {

    private static final String MCP_TALK = "Practical MCP Security";

    private record Rule(List<String> phrases, String tool, Map<String, Object> args) {
        boolean matches(String prompt) {
            return phrases.stream().allMatch(prompt::contains);
        }
    }

    // Most specific first: "undo ... cancel" must win over "cancel".
    private static final List<Rule> RULES = List.of(
            new Rule(List.of("undo", "cancel"), "cancel_my_session",
                    args("session_query", MCP_TALK, "reason", "back on track", "undo", true)),
            // Demo 3 injection beat: another speaker's talk, refused by the API's ownership check.
            new Rule(List.of("cancel", "graalvm"), "cancel_my_session",
                    args("session_query", "GraalVM", "reason", "rescheduled by organisers")),
            new Rule(List.of("cancel"), "cancel_my_session",
                    args("session_query", MCP_TALK, "reason", "I want to go home early")),
            new Rule(List.of("signed up"), "view_session_attendees", args("session_query", MCP_TALK)),
            new Rule(List.of("feedback"), "my_session_feedback", Map.of()),
            new Rule(List.of("rate"), "rate_session",
                    args("session_query", MCP_TALK, "stars", 5, "comment", "great use of caffeine")),
            new Rule(List.of("conflict"), "my_conflicts", Map.of()),
            new Rule(List.of("agenda"), "my_agenda", Map.of()),
            new Rule(List.of("bookmark", "graalvm"), "bookmark_session", args("session_query", "GraalVM")),
            new Rule(List.of("add", "mcp security"), "bookmark_session", args("session_query", MCP_TALK)),
            new Rule(List.of("find", "mcp"), "list_sessions", args("query", "MCP")),
            new Rule(List.of("coming up next"), "whats_next", Map.of()),
            new Rule(List.of("right now"), "whats_on_now", Map.of()));

    public Optional<LlmToolPlanner.Plan> plan(String userPrompt) {
        String prompt = userPrompt == null ? "" : userPrompt.toLowerCase(Locale.ROOT);
        return RULES.stream()
                .filter(r -> r.matches(prompt))
                .findFirst()
                .map(r -> LlmToolPlanner.Plan.tool(r.tool(), r.args(),
                        "Scripted fallback (LLM unavailable) picked tool " + r.tool() + "."));
    }

    private static Map<String, Object> args(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
