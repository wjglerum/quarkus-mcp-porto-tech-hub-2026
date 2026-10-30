package nl.lunatech.porto.mcp.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SessionResolverTest {

    @Test
    void dropsTheWordsAModelAddsAroundATitle() {
        assertEquals("MCP Security", SessionResolver.stripFiller("my MCP Security talk"));
        assertEquals("GraalVM", SessionResolver.stripFiller("the GraalVM session"));
    }

    @Test
    void keepsTheQueryWhenItIsOnlyFiller() {
        assertEquals("my talk", SessionResolver.stripFiller("  my talk "));
    }
}
