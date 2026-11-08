package nl.lunatech.porto.api.dto;

/**
 * A step-up tool that conference-mcp refused before calling the API, reported so the refusal is
 * audited. {@code detail} carries the arguments the agent sent, such as the reason it gave.
 */
public record StepUpRefusalRequest(String tool, String detail) {}
