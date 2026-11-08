# Design

The security decisions behind the demo, and the shortcuts it takes on purpose. The architecture and request flow are in [README.md](README.md); the stage script is in [RUNBOOK.md](RUNBOOK.md).

## Tool tiers

Every MCP tool sits in one tier. The tier decides what the token must carry, and the API checks the same thing again.

| Tier | Tools | Requires | Audited |
|------|-------|----------|---------|
| Public | `list_sessions`, `get_session`, `whats_on_now`, `whats_next` | a token with the `attendee` scope (the whole `/mcp` endpoint) | no |
| Attendee | `bookmark_session`, `unbookmark_session`, `my_agenda`, `my_conflicts`, `rate_session`, `my_ratings` | role `attendee` | writes only |
| Speaker | `my_session_feedback` | role `speaker` | no; ratings are shown without attendee names |
| Speaker, step-up | `view_session_attendees`, `cancel_my_session` | role `speaker` and `acr=urn:porto:mfa` | yes, refusals included |

Read tools don't write audit rows: the log records intent, not curiosity. A refused attempt (another speaker's session) is written in its own transaction, so the rollback of the refused request doesn't erase it.

## Trust boundaries

- **The user's token stops at the MCP server.** Its audience is `conference-mcp`. For every API call the MCP server exchanges it (RFC 8693) for a token for the same user with `aud=conference-api` and `azp=conference-mcp`. The API rejects any other audience, so a user token sent straight to the API fails.
- **The API authorises on its own.** Roles, session ownership and step-up are checked in `conference-api`, not only in the MCP tools. A tool is only an entry point.
- **Step-up is checked where the `acr` is visible.** Keycloak's token exchange drops the `acr`, so `conference-mcp` enforces MFA on the user's own token and refuses with `insufficient_user_authentication` before calling the API. The API keeps its own RFC 9470 check (401 with `WWW-Authenticate`) as a second line.
- **Destructive actions are explicit.** `cancel_my_session` cancels, and `undo=true` restores. Repeating either fails with 409 instead of flipping the state back, so a retried call can't silently undo the last one.

## Client identity

- `conference-chat` is a confidential web-app client: code flow with PKCE, RP-initiated logout.
- MCP Inspector uses CIMD where it can: the `client_id` is the URL of a metadata document served by `conference-mcp`, and Keycloak fetches it.
- DCR also works. The realm clears three anonymous registration policies to make it usable, and keeps **Consent Required** on, so an unvetted client never gets a token without the user seeing a consent screen.

## Demo shortcuts

These are deliberate, and each should be called out if someone asks:

- **Trusted hop headers.** `conference-mcp` tells the API the `acr` it verified and the client the user signed in with (`X-Mcp-Acr`, `X-Mcp-Client`). The API honours them only on tokens issued to `conference-mcp`. The proper fix is token exchange delegation with an `act` claim, still preview in Keycloak 26.8.
- **CIMD over plain HTTP.** The spec requires an HTTPS `client_id`. The demo serves it over HTTP on `host.docker.internal`.
- **Open audit feed.** `/api/v1/audit/recent` and `/audit-live/` need no login so they can run on the second screen. A real deployment puts them behind an auditor role.
- **Scripted fallback planner.** If the LLM is unreachable, `conference-chat` maps the rehearsed prompts to tool calls itself and says so in the reply. The MCP calls, tokens and audit trail are identical.
- **Demo clock.** `DEMO_NOW` pins "now" to 10 November 2026, 11:40 venue time, so `whats_on_now` and the rating cutoff behave the same in every rehearsal.

## Production configuration

The `%prod` profile has no fallback values for the auth server URL, client secrets or database credentials. A missing variable fails startup instead of silently using a demo value.
