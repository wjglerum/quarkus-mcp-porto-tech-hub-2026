# Practical MCP Security in Action
### Porto Tech Hub 2026 / Conference Companion / Demo backend

> "It wasn't the AI. It was me."
>
> Three demos, three tiers. All against the same Quarkus MCP server and the same audit log.

## Layout

| Path | What it is |
|------|------------|
| `conference-api/`   | Quarkus REST + Panache + Postgres. Owns the schedule, attendee agendas, ratings, and the audit log. Serves the live audit dashboard at `/audit-live`. |
| `conference-mcp/`   | Quarkus MCP server (streamable HTTP). Exposes tools to AI clients and exchanges the user OAuth token (RFC 8693) for a `conference-api` token on every call. |
| `conference-chat/`  | Quarkus chat client. OIDC web-app login, LangChain4j MCP client to `conference-mcp`, Qute-rendered shell. |
| `keycloak-realm.json` | Single shared realm export at the monorepo root. All three apps reference it via `../keycloak-realm.json`. |
| `SPEC.md`           | Original build spec for the demo (canonical reference). |
| `RUNBOOK.md`        | Stage-side runbook with the three-demo flow and the env vars to set. |
| `docs/slides/`      | The talk deck as a self-contained HTML page. |

No Docker compose file: every backing service is auto-provisioned by **Quarkus Dev Services**.

## Architecture

Three Quarkus apps share one Keycloak realm. Clients authenticate against Keycloak (chat via OIDC code flow + PKCE, MCP Inspector via DCR or CIMD + PKCE), then call the MCP server with a Bearer token. The MCP server validates the token, then exchanges it at Keycloak for a token minted for `conference-api` (RFC 8693 token exchange) and calls the API with that. `conference-api` enforces roles and step-up and writes the audit log.

```mermaid
graph LR
  subgraph clients[Clients]
    USR[Browser user]
    INS[MCP Inspector]
  end

  CHAT["conference-chat :8082<br/>OIDC web app + LangChain4j MCP client"]
  MCP["conference-mcp :8081<br/>MCP server + tools"]
  API["conference-api :8080<br/>REST + Panache + audit + dashboard"]
  KC[("Keycloak 26<br/>porto realm")]
  DB[("Postgres<br/>schedule, agendas, audit")]

  USR -->|HTTPS| CHAT
  INS -->|MCP streamable HTTP + Bearer| MCP
  CHAT -->|MCP + user token| MCP
  CHAT -->|OIDC code flow + PKCE| KC
  INS -->|DCR or CIMD + PKCE| KC
  MCP -->|token exchange RFC 8693| KC
  MCP -->|REST + exchanged Bearer| API
  MCP -.->|validate JWT| KC
  API -.->|validate JWT| KC
  API --> DB
```

The heart of the demo is identity propagation without token passthrough. The user's token (audience `conference-mcp`) stops at the MCP server. For each API call the MCP server exchanges it for a new token for the same user with audience `conference-api` and `azp=conference-mcp`. The action is authorized and audited under the user's identity, and the audit log also names the client that acted (`conference-chat via conference-mcp`).

```mermaid
sequenceDiagram
  actor User
  participant C as Client (chat / Inspector)
  participant KC as Keycloak
  participant MCP as conference-mcp
  participant API as conference-api
  participant DB as Postgres

  User->>C: "Bookmark the GraalVM talk"
  Note over C,KC: First time only: PKCE (plus DCR for Inspector)
  C->>KC: Authorization Code + PKCE
  KC-->>C: Access token (roles, preferred_username)
  C->>MCP: tools/call bookmark_session + Bearer
  MCP->>KC: validate JWT
  MCP->>KC: token exchange: subject_token=user token, audience=conference-api
  KC-->>MCP: token for the same user, aud=conference-api, azp=conference-mcp
  MCP->>API: POST /me/agenda + exchanged Bearer + X-Mcp-Acr / X-Mcp-Client
  API->>API: enforce @RolesAllowed("attendee")
  API->>DB: insert bookmark + audit_event
  API-->>MCP: 200 OK
  MCP-->>C: tool result
  C-->>User: "Bookmarked."
  Note over API,DB: audit_event attributed to preferred_username, not the AI
```

## MCP protocol version

`conference-mcp` runs on [Quarkus MCP Server](https://docs.quarkiverse.io/quarkus-mcp-server/dev/) 2.0.4 (Quarkus 3.40.1), which implements the latest MCP spec, **2026-07-28**. The headline change in that spec is the **stateless** mode: a client skips the `initialize`/`initialized` handshake, can call `server/discover` to learn the supported versions and capabilities, and sends every request self-contained (`MCP-Protocol-Version` and `Mcp-Method` headers plus `_meta` client info). No `Mcp-Session-Id` is issued, so there is no session to go stale after a reload (MCP Inspector and the chat client still run in session mode).

The same `/mcp` endpoint still serves session-based clients on 2025-11-25 and earlier (MCP Inspector, the LangChain4j client in `conference-chat`). Authorization is unchanged across both modes: every request, stateless or not, carries the Bearer token and passes the same gate. `McpProtocolVersionTest` pins this behaviour.

## MCP endpoint authorization

The whole `/mcp` endpoint is gated at the HTTP layer: every request must carry a valid Bearer token whose realm roles include `attendee`. `conference-mcp` runs `quarkus-oidc` in `service` mode (it validates the JWT against the Keycloak JWKS, no client id or secret needed) and adds the `quarkus-mcp-server-oidc` extension, which turns authorization failures into the challenges the MCP authorization spec expects:

- **No token** returns `401` with a `WWW-Authenticate: Bearer ..., resource_metadata="..."` header. The `resource_metadata` URL is the RFC 9728 protected-resource document at `/.well-known/oauth-protected-resource`, and it is what drives an MCP client's discovery and OAuth flow.
- **A valid token without the `attendee` scope** returns `403` with `WWW-Authenticate: Bearer error="insufficient_scope", scope="attendee", resource_metadata="..."`, telling the client exactly which scope to request.

On top of the role check, `conference-mcp` validates the token **audience**: only tokens carrying `aud=conference-mcp` are accepted (`quarkus.oidc.token.audience`), as the MCP authorization spec requires of resource servers. Keycloak has no RFC 8707 resource-indicator support yet, so the audience comes from the realm's `attendee` client scope, which carries audience mappers for `conference-mcp` and `conference-api` (the officially documented Keycloak workaround). `conference-chat` has that scope by default; DCR and CIMD clients request `scope=attendee`, which is exactly what the challenge and the resource metadata advertise. A token from another client in the realm without that scope is rejected with a `401` even if its user has the role, closing the confused-deputy hole of accepting any realm token.

This is the endpoint-level gate. It is distinct from the step-up case in demo 3, where a missing MFA `acr` surfaces per tool as `insufficient_user_authentication`.

## Token exchange and step-up across the hop

`conference-mcp` never forwards the user's token. `quarkus-rest-client-oidc-token-propagation` with `exchange-token=true` performs an RFC 8693 token exchange at Keycloak before every `conference-api` call. Keycloak allows it only because `conference-mcp` is a confidential client with standard token exchange enabled and the `conference-api` audience in its default client scopes. Any other requested audience is refused. `conference-api` validates `aud=conference-api` in every profile, so a user token sent straight to the API is rejected with a 401.

Keycloak's standard token exchange keeps the user, the roles and the session, but **not the step-up level**: an `acr` of `urn:porto:mfa` comes back as `1` (verified against Keycloak 26.7.4; `scope`, `acr_values` and `claims` on the exchange request do not change it). So the hop that saw the original token enforces and asserts it:

- `conference-mcp` checks the `acr` on the user's token before any step-up tool runs, as before.
- It sends what it verified as `X-Mcp-Acr` and the client the user signed in with as `X-Mcp-Client`.
- `conference-api` trusts those headers **only** on a token issued to `conference-mcp` (`azp`). Only that client holds the secret to obtain one. For any other caller, the token's own `acr` is all that counts.

Keycloak 26.7 adds token exchange *delegation* with an `act` claim, as an experimental feature. Until that is stable, `azp` plus the asserted client header is how the audit log names both the user and the agent.

## Client registration: DCR vs CIMD

An AI client has to obtain an OAuth client identity before it can run PKCE. The demo shows both mechanisms against the same realm: CIMD, the default, and DCR, which the 2026-07-28 spec deprecates and keeps only for backwards compatibility.

**Dynamic Client Registration (DCR)** has the client POST to a writeable registration endpoint and get back a generated `client_id`. To make this work for MCP Inspector, the realm clears four anonymous registration policies (documented in [`RUNBOOK.md`](RUNBOOK.md)). The talking point: "the AI got a token" is not "the AI got a useful token", every scope, role, and claim has to be consciously engineered back in.

**Client ID Metadata Documents (CIMD)**, the spec-preferred default since the MCP 2025-11-25 spec (SEP-991), drop the registration step entirely. The `client_id` is an HTTPS URL, and Keycloak fetches the client metadata document at that URL. The demo serves one at `conference-mcp/.../META-INF/resources/cimd/mcp-inspector.json`, enabled through Keycloak's experimental `cimd` feature (`quarkus.keycloak.devservices.features=cimd`).

| | DCR | CIMD |
|--|-----|------|
| Client identity | Generated `client_id` from a registration call | An HTTPS URL that is the `client_id` |
| Server state | A stored registration record per client | None, the document is fetched on demand |
| Registration endpoint | Writeable, must be opened and policed | None |
| Realm setup for this demo | Four anonymous policies cleared | One experimental feature flag enabled |
| Spec status | Deprecated in 2026-07-28, kept for compatibility | Preferred default since 2025-11-25 |

## Run

```bash
# Terminal 1 - conference-api on :8080
cd conference-api && ./mvnw quarkus:dev

# Terminal 2 - conference-mcp on :8081
cd conference-mcp && ./mvnw quarkus:dev

# Terminal 3 - conference-chat on :8082
cd conference-chat && ./mvnw quarkus:dev
```

On first start Dev Services boots:

- a **Postgres 16** container for `conference-api` (schema owned by Hibernate via `drop-and-create`, auto-seeded with the schedule and demo data)
- a **Keycloak 26** container with the `porto` realm pre-imported, shared between all three apps via `service-name=porto-keycloak`

The realm export lives once at the monorepo root (`./keycloak-realm.json`). All three apps reference it via `quarkus.keycloak.devservices.realm-path=../keycloak-realm.json`.

Dev UI for each app lives at `http://localhost:8080/q/dev/`, `http://localhost:8081/q/dev/`, and `http://localhost:8082/q/dev/`.

Playwright end-to-end tests live inside `conference-chat`: run them with `cd conference-chat && ./mvnw test`.

## Demo dashboard

`http://localhost:8080/audit-live/` shows the second-screen view used in demos 2 and 3. The styling matches the talk deck: dark surface, brand blue for normal events, amber for step-up tools, red for destructive actions, mono font for `audit_event` rows.

Open the page on a secondary monitor and run the demos. New events appear within two seconds of every tool call.

## Demos

See [`RUNBOOK.md`](RUNBOOK.md) for the moment-by-moment script of the three live demos:

1. **Public** browse the schedule. PKCE + Authorization Code Flow, with client identity via DCR and via CIMD.
2. **Personal** agenda, ratings, conflicts. Token exchange per hop, full audit.
3. **Sensitive** speaker-only tools with step-up MFA. Server-driven challenge.

## Brand palette (lifted from the deck)

| Role | Hex | Where it shows up |
|------|-----|-------------------|
| Background    | `#0E1116` | Audit dashboard surface |
| Surface       | `#171B22` | Cards, top bar |
| Border        | `#2A313C` | Card outlines |
| Primary text  | `#F5F7FA` | Headings, key values |
| Muted text    | `#9AA4B2` | Subtitles, field labels |
| Brand         | `#0088D3` | Normal actions, links, accents |
| Amber         | `#F2A65A` | Step-up / sensitive actions, identity highlights |
| Body type     | Calibri / system sans | Headings, prose |
| Mono type     | Consolas / JetBrains Mono | `audit_event`, identifiers |

## Conventions

- No em dashes anywhere in code, comments, or docs (Lunatech house style).
- Tests run against Quarkus Dev Services (Testcontainers under the hood); no manual infra needed for `mvn test`.
- Hibernate owns the schema (`drop-and-create` in dev/test). No Flyway.
