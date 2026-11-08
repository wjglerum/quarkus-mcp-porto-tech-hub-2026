package nl.lunatech.porto.chat.web;

import dev.langchain4j.model.chat.ChatModel;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import nl.lunatech.porto.chat.llm.LlmToolPlanner;
import nl.lunatech.porto.chat.llm.ScriptedPlanner;
import nl.lunatech.porto.chat.web.ToolDispatcher.ToolResult;

@Path("/api/chat")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
public class ChatResource {

    @Inject
    ToolDispatcher dispatcher;

    @Inject
    LlmToolPlanner planner;

    @Inject
    ScriptedPlanner scripted;

    @Inject
    ChatModel chatModel;

    /** "llm" (default) asks the model and falls back to the scripted planner on failure; "scripted" skips the model. */
    @ConfigProperty(name = "demo.planner", defaultValue = "llm")
    String plannerMode;

    @POST
    @Path("/send")
    public ChatTurn send(ChatRequest req) {
        String prompt = req == null || req.prompt() == null ? "" : req.prompt().trim();
        if (prompt.isEmpty()) {
            return ChatTurn.text("Type a prompt to get started.");
        }

        LlmToolPlanner.Plan plan = "scripted".equalsIgnoreCase(plannerMode)
                ? scripted.plan(prompt).orElse(LlmToolPlanner.Plan.text(
                        "Scripted mode only knows the demo prompts in the sidebar."))
                : planner.plan(chatModel, prompt);
        if (plan.error() != null) {
            LlmToolPlanner.Plan failedLlm = plan;
            plan = scripted.plan(prompt).orElse(null);
            if (plan == null) {
                return new ChatTurn(prompt, failedLlm.error(), null);
            }
        }
        if (!plan.hasTool()) {
            return new ChatTurn(prompt, plan.note(), null);
        }
        ToolResult result = dispatcher.invoke(plan.tool(), plan.args());
        return new ChatTurn(prompt, plan.note(), result);
    }

    public record ChatRequest(String prompt) {}

    public record ChatTurn(String prompt, String note, ToolResult result) {
        public static ChatTurn text(String note) {
            return new ChatTurn(null, note, null);
        }
    }
}
