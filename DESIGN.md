# Design

The security decisions behind the demo, and the shortcuts it takes on purpose. The architecture and request flow are in [README.md](README.md); the stage script is in [RUNBOOK.md](RUNBOOK.md).

## Tool tiers

Every MCP tool sits in one tier. The tier decides what the token must carry, and the API checks the same thing again.

"Public" means public data, not anonymous access: the schedule is open on `conference-api`, but every MCP call needs a token, because the whole `/mcp` endpoint is gated. An MCP server that reaches private tools on the same endpoint should not have an unauthenticated door.

| Tier | Tools | Requires | Audited |
|------|-------|----------|---------|
| Public | `list_sessions`, `get_session`, `whats_on_now`, `whats_next` | a token with the `attendee` scope (the whole `/mcp` endpoint) | no |
| Attendee | `bookmark_session`, `unbookmark_session`, `my_agenda`, `my_conflicts`, `rate_session`, `my_ratings` | role `attendee` | writes only |
| Speaker | `my_session_feedback` | role `speaker` | no; ratings are shown without attendee names |
| Speaker, step-up | `view_session_attendees`, `cancel_my_session` | role `speaker` and `acr=urn:porto:mfa` | yes, including the API's refusals |

Read tools don't write audit rows; only writes are logged. A refused attempt (another speaker's session) is written in its own transaction, so the rollback of the refused request doesn't erase it.

A missing step-up is refused earlier, in `conference-mcp`, before the step-up endpoint is called: only it sees the user's own `acr`. It reports the refusal to `POST /api/v1/audit/refusals` first, which records `CANCEL_SESSION_ATTEMPTED_WITHOUT_STEP_UP` or `VIEW_SESSION_ATTENDEES_ATTEMPTED_WITHOUT_STEP_UP` with the arguments the agent sent. During a prompt injection that row is the first sign that something asked for more than the user did. The endpoint accepts reports only on tokens issued to `conference-mcp` and only for the two step-up tools, so nothing else can write to the log through it. The report is best effort: if it fails, the refusal still stands.

## Untrusted content

`my_session_feedback` returns comments that attendees wrote, so attendee text reaches the speaker's agent, and that agent can also call `cancel_my_session`. The demo seeds one comment with an injected instruction (`DemoDataSeeder.INJECTED_COMMENT`) to show this on stage. What contains it:

- **Step-up.** Before MFA, the injected call stops at `conference-mcp` and the client shows an unexpected step-up prompt.
- **Ownership in the API.** After MFA, an injected call still cannot touch another speaker's session, and that refusal is audited.
- **What does not contain it.** After MFA, an injected cancel of the speaker's *own* session goes through: `conference-mcp` checks the `acr` claim, not how recent the MFA was. The fix is per-action confirmation in the client, which this demo does not have.

## Trust boundaries

- **The user's token stops at the MCP server.** Its audience is `conference-mcp`. For every API call the MCP server exchanges it (RFC 8693) for a token for the same user with `aud=conference-api` and `azp=conference-mcp`. The API rejects any other audience, so a user token sent straight to the API fails.
- **The agent acts with the user's consent, and the token says so.** The exchange is delegated: the user consented at login to `delegation:client:conference-mcp`, Keycloak checked that `conference-mcp` holds the `delegate` permission (FGAP v2), and the exchanged token carries `act: {client_id: conference-mcp}`. The audit log records it as `token_act`. A token without that consent gets the standard exchange and no `act`, so the audit shows the difference.
- **The API authorises on its own.** Roles, session ownership and step-up are checked in `conference-api`, not only in the MCP tools. A tool is only an entry point.
- **Step-up is checked where the `acr` is visible.** Keycloak's token exchange drops the `acr`, so `conference-mcp` enforces MFA on the user's own token and refuses with `insufficient_user_authentication` before calling the API. The API keeps its own RFC 9470 check (401 with `WWW-Authenticate`) as a second line.
- **Destructive actions are explicit.** `cancel_my_session` cancels, and `undo=true` restores. Repeating either fails with 409 instead of flipping the state back, so a retried call can't silently undo the last one.

## Client identity

- `conference-chat` is a confidential web-app client: code flow with PKCE, RP-initiated logout. It has **Consent Required** on, because Keycloak only grants a delegation scope to a client that asks the user; the consent screen comes back on every login and step-up, since delegation consent is never stored.
- MCP Inspector uses CIMD where it can: the `client_id` is the URL of a metadata document served by `conference-mcp`, and Keycloak fetches it. The document lists `delegation:client`, so Inspector gets the delegation consent too.
- DCR also works. The realm clears three anonymous registration policies to make it usable, and keeps **Consent Required** on, so an unvetted client never gets a token without the user seeing a consent screen. Keycloak's DCR matches scope names literally: a client must register with `delegation:client`, not `delegation:client:conference-mcp`, or its delegated login fails with `invalid_scope`.

## Demo shortcuts

These are deliberate, and each should be called out if someone asks:

- **Trusted hop headers.** `conference-mcp` tells the API the `acr` it verified and the client the user signed in with (`X-Mcp-Acr`, `X-Mcp-Client`). The API honours them only when the token's acting client (`act`, else `azp`) is `conference-mcp`. Delegation puts the agent in the token, but the delegated token still carries `acr=1` and not the originating client, so these headers stay. The proper fix needs the authorization server to carry the step-up level across the exchange and record the chain of clients.
- **Delegation is a preview feature.** Token exchange delegation and parameterized scopes are preview in Keycloak 26.8, enabled through Dev Services (`quarkus.keycloak.devservices.features`). A production realm needs the same features switched on, the `delegate` permission, and `conference-mcp`'s service account.
- **CIMD over plain HTTP.** The spec requires an HTTPS `client_id`. The demo serves it over HTTP on `host.docker.internal`, which needs two more demo-only settings: the realm's `cimd-profile` has **Allow http scheme** on (and `cimd-policy` matches `http` client ids), and `conference-mcp` accepts the `Host: host.docker.internal` header in dev (`%dev.quarkus.http.host-validation.allowed-hosts`) so Keycloak's fetch from inside its container isn't rejected. A real deployment serves the metadata document over HTTPS on a public host and needs neither.
- **Open audit feed.** `/api/v1/audit/recent` and `/audit-live/` need no login so they can run on the second screen. A real deployment puts them behind an auditor role.
- **Scripted fallback planner.** If the LLM is unreachable, `conference-chat` maps the rehearsed prompts to tool calls itself and says so in the reply. The MCP calls, tokens and audit trail are identical.
- **Demo clock.** `DEMO_NOW` pins "now" to 10 November 2026, 11:40 venue time, so `whats_on_now` and the rating cutoff behave the same in every rehearsal.

## Production configuration

The `%prod` profile has no fallback values for the auth server URL, client secrets or database credentials. A missing variable fails startup instead of silently using a demo value.
