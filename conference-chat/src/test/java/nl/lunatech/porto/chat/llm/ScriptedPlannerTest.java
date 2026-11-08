package nl.lunatech.porto.chat.llm;

import nl.lunatech.porto.chat.web.QuickPrompts;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptedPlannerTest {

    private final ScriptedPlanner planner = new ScriptedPlanner();

    @Test
    void everyQuickPromptMapsToItsSuggestedTool() {
        for (QuickPrompts.QuickPrompt q : QuickPrompts.all()) {
            LlmToolPlanner.Plan plan = planner.plan(q.label()).orElseThrow(
                    () -> new AssertionError("no scripted plan for: " + q.label()));
            assertEquals(q.suggestedTool(), plan.tool(), q.label());
        }
    }

    @Test
    void runbookPromptsOutsideTheSidebarAreCovered() {
        assertEquals("bookmark_session",
                planner.plan("Also add the Practical MCP Security talk.").orElseThrow().tool());
        LlmToolPlanner.Plan undo = planner.plan(
                "Undo the cancellation of my MCP Security talk, reason: back on track.").orElseThrow();
        assertEquals("cancel_my_session", undo.tool());
        assertEquals(true, undo.args().get("undo"));
        LlmToolPlanner.Plan other = planner.plan(
                "Cancel the GraalVM talk, reason: rescheduled by organisers.").orElseThrow();
        assertEquals("cancel_my_session", other.tool());
        assertEquals("GraalVM", other.args().get("session_query"));
    }

    @Test
    void unknownPromptsAreNotGuessed() {
        assertTrue(planner.plan("Write me a poem about Porto").isEmpty());
    }
}
