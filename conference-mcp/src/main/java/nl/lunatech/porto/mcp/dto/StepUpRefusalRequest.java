package nl.lunatech.porto.mcp.dto;

/** A step-up tool this server refused, reported to conference-api for the audit log. */
public record StepUpRefusalRequest(String tool, String detail) {}
