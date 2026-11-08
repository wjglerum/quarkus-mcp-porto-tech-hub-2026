# Runbook: Practical MCP Security in Action

The stage-side runbook for the three live demos at Porto Tech Hub 2026.

## Stage assumptions

- Laptop with Docker (for Dev Services), Java 25, Maven.
- Three terminal windows: `conference-api` (:8080), `conference-mcp` (:8081), `conference-chat` (:8082).
- Second monitor showing the **live audit dashboard** at `http://localhost:8080/audit-live/`.
- One browser window on the chat client at `http://localhost:8082/`.
- One backup video per demo at 1.5x speed in case the live run misbehaves.

## Pre-flight: step-up demo prerequisite

The seeded realm ships a TOTP credential for `willem.jan` and a `Browser Stepup` authentication flow that maps `urn:porto:mfa` to LoA 2 (OTP, re-prompted after 5 minutes). Keycloak stores the raw secret `portomcp2026stepupseed`; authenticator apps want its Base32 form. Add it to a TOTP app on your phone before the talk (manual entry, SHA1, 6 digits, 30s):

```
OBXXE5DPNVRXAMRQGI3HG5DFOB2XA43FMVSA
```

The seed changed from the jPrime realm, so an existing jPrime entry on the phone will produce wrong codes. Check the phone clock is synced; TOTP fails on drift.

What the audience sees in Demo 3: `conference-mcp` checks the `acr` on your token **before** it calls the API, so the refusal is a tool error, `insufficient_user_authentication: this tool requires step-up MFA`, visible in the `conference-mcp` terminal. The chat recognises that error code, shows the amber step-up card and opens the OTP popup. The API's own 401 + `WWW-Authenticate` challenge is a second line of defence and does not appear in this flow, so don't promise it on stage. MCP Inspector and Claude show the tool error but will not re-prompt for MFA by themselves.

## Env vars

| Variable | Purpose | Demo default |
|----------|---------|--------------|
| `DEMO_NOW` | Override the "current time" used by `whats_on_now`, `whats_next`, and the rating cutoff. | `2026-11-10T11:40:00+00:00` |
| `ANTHROPIC_API_KEY` | The LLM behind `conference-chat`. Without it the chat falls back to the scripted planner. | required in Terminal 3 |
| `DEMO_PLANNER` | `llm` asks the model and falls back to scripted prompts if it fails or takes over 15s. `scripted` never calls the model: use it when the wifi is gone. | `llm` |

`OIDC_AUTH_SERVER_URL`, `CONFERENCE_API_URL`, `CONFERENCE_MCP_URL`, and database credentials are only needed in `%prod`. In dev mode Quarkus Dev Services wires everything automatically.

## Boot order

```bash
# Terminal 1 - starts Postgres + Keycloak Dev Services on first boot
cd conference-api && DEMO_NOW=2026-11-10T11:40:00+00:00 ./mvnw quarkus:dev

# Terminal 2 - joins the shared Keycloak container
cd conference-mcp && DEMO_NOW=2026-11-10T11:40:00+00:00 ./mvnw quarkus:dev

# Terminal 3 - chat client, joins the same Keycloak container
export ANTHROPIC_API_KEY=...
cd conference-chat && DEMO_NOW=2026-11-10T11:40:00+00:00 ./mvnw quarkus:dev
```

The first start pulls the Postgres and Keycloak images. Allow 30 to 60 seconds on a fresh machine; subsequent restarts reuse the containers.

Open `http://localhost:8080/audit-live/` on the second monitor.

## Pre-flight check (before going on stage)

1. `curl http://localhost:8080/api/v1/sessions | jq '.[0]'` returns a real session.
2. The audit dashboard shows the five seeded attendee events (alice, bob, carol, dave, erin) and nothing from `willem.jan`. Any `willem.jan` row is rehearsal data: reset Postgres (see Recovery).
3. Open the Dev UI at `http://localhost:8080/q/dev/` and click the **Keycloak** tile. Confirm realm `porto` is imported with `attendee`, `willem.jan`, and `admin-demo`.
4. From the Dev UI Keycloak tile, copy the realm URL. Paste `http://<dev-keycloak-url>/.well-known/openid-configuration` into a browser tab and confirm 200.
5. Hit `http://localhost:8082/` in a browser; log in as `attendee / attendee`. The Qute shell renders with the quick prompts. Click *"What's happening right now?"* once: a real answer proves the API key and the MCP connection.
6. Start the apps (and so Keycloak) at least 15 minutes before the slot; the first container start is the slow one.
7. Venue wifi: tether to a phone hotspot for the LLM. Run `npx -y @modelcontextprotocol/inspector@latest` once beforehand so the download is cached, and leave Inspector's `porto-conference` server configured for CIMD (see "Drive it from Inspector").
8. Do one full dry run after a clean Postgres restart (see Recovery) so the seeds and the cancellation are reset. Then restart once more: the dry run leaves your bookmarks, rating and cancellation behind.
9. Notifications off, sleep off, backup videos queued.
10. LLM fallback: if the model fails, the chat answers the sidebar and runbook prompts from a scripted planner and says so in the reply ("Scripted fallback (LLM unavailable)"). With no network at all, restart `conference-chat` with `DEMO_PLANNER=scripted` so nothing waits on a timeout. Every tool also runs from MCP Inspector.

## Time budget (50 minutes)

| Block | Minutes |
|-------|---------|
| Slides before Demo 1 | 8 |
| Demo 1, including CIMD live | 7 |
| Slides between, including DCR versus CIMD | 6 |
| Demo 2 | 7 |
| Slides between | 5 |
| Demo 3, including the injection beat | 8 |
| Wrap-up | 4 |
| Buffer / Q&A | 5 |

The demo headings below carry the same minutes as this table. If a rehearsal runs over, cut from the demo, not from Buffer / Q&A.

## Client registration on slides

CIMD is shown live at the end of Demo 1. DCR is not: its Keycloak policy details are interesting to people who run Keycloak and noise to everyone else. The slides between Demo 1 and Demo 2 put the two side by side and carry:

- DCR: the client POSTs to a writeable registration endpoint. Making it work for Inspector meant clearing three anonymous registration policies; Consent Required stays on as the last guard. Talking point: "the AI got a token" is not "the AI got a useful token".
- CIMD: the `client_id` is a URL, Keycloak fetches the metadata document, nothing is stored.
- The comparison table from "DCR vs CIMD, the punchline" below, and its punchline: DCR keeps a writeable door open and asks you to lock it down, CIMD removes the door.
- Spec status: CIMD recommended since 2025-11-25, DCR deprecated in 2026-07-28 and kept only for authorization servers that do not support CIMD yet. Keycloak is that case today: CIMD is an experimental feature flag, which is why the realm still allows DCR.

The DCR section below stays as rehearsal material and as the backup if someone asks to see it live in Q&A. The CIMD section is what Demo 1 step 6 runs.

## MCP Inspector and Dynamic Client Registration (DCR)

Point MCP Inspector at the streamable HTTP endpoint `http://localhost:8081/mcp` (not `/mcp/sse`; the SSE transport is deprecated in current Inspector builds). Use the **Guided** or **Quick OAuth Flow** in Inspector's Auth panel. The flow runs entirely off discovery: Inspector reads `/.well-known/oauth-protected-resource` from `conference-mcp`, finds the Keycloak realm, registers a fresh client via DCR, then runs PKCE and sends you to the Keycloak login.

The whole `/mcp` endpoint requires the `attendee` scope, so the first unauthenticated request returns a `401` whose `WWW-Authenticate` header carries the `resource_metadata` pointer. That is the spec-compliant signal that starts the discovery above. A token that authenticates but lacks the `attendee` scope returns a `403` with `error="insufficient_scope", scope="attendee"`, emitted by the `quarkus-mcp-server-oidc` extension. Both challenges name the resource metadata URL so the client knows where to go next.

The `porto` realm is deliberately configured for **open anonymous DCR** so Inspector can register with no pre-shared credentials. Keycloak ships four anonymous client-registration policies that each block or cripple a DCR client. Three are cleared in `keycloak-realm.json`; Consent Required stays on deliberately:

| Removed/changed policy | Why it had to go |
|------------------------|------------------|
| Allowed Client Scopes | Rejected the `openid` scope Inspector always requests. |
| Trusted Hosts | Rejected Inspector's hardcoded `github.com` `client_uri`, and the Docker gateway sender IP. |
| Full Scope Disabled | Set `fullScopeAllowed=false`, stripping realm roles from the token (caused 403 on tool calls). |
| Consent Required (kept) | Every freshly registered client gets a consent screen. An anonymous client nobody vetted must not get a token silently: that is the confused-deputy risk the MCP spec warns about. On stage, click **Yes** and point out that this prompt is a feature. |

On top of that, the `basic` client scope carries `realm-roles` and `preferred_username` mappers. DCR clients only retain the `basic` scope, so without these the token would be claimless: no roles (tool authorization fails) and no username (audit events attributed to the `sub` UUID).

Talking point: **"the AI got a token" is not "the AI got a useful token".** Keycloak strips scopes, roles, and identity from dynamically registered clients by default. Every claim the agent acts on has to be consciously engineered in.

**Reconnect after any reload.** MCP sessions live in the `conference-mcp` process. Inspector and the chat client still use session mode (2025-11-25); stateless 2026-07-28 clients have no session to lose. Whenever it hot-reloads or restarts, Inspector's session id goes stale and every POST fails with `MCP error -32099: Streamable HTTP error: Error POSTing to endpoint (HTTP 404)`. Fix: click **Disconnect** then **Connect** in Inspector to re-run `initialize`. The OAuth token stays valid, so no re-login.

## MCP Inspector and Client ID Metadata Documents (CIMD)

CIMD is the spec-preferred successor to DCR, introduced in the MCP 2025-11-25 authorization spec (SEP-991). Instead of registering a client by POSTing to a writeable registration endpoint, the client presents an HTTPS URL as its `client_id`. The authorization server fetches the client metadata document at that URL, validates the redirect URIs, and runs PKCE as normal. There is no registration record to create, store, or police.

This demo ships a static client metadata document for MCP Inspector at:

```
conference-mcp/src/main/resources/META-INF/resources/cimd/mcp-inspector.json
```

served at `http://host.docker.internal:8081/cimd/mcp-inspector.json`. That same URL is the `client_id` value inside the document, so the two match exactly as the spec requires.

One deliberate spec deviation to mention if asked: the CIMD draft requires the `client_id` URL to use the `https` scheme. Plain `http` works here only because the whole demo runs on Docker loopback; any real deployment must serve the metadata document over HTTPS.

### Why host.docker.internal

Keycloak runs inside a Dev Services container and must fetch the metadata document itself. From inside the container the host is reachable as `host.docker.internal`, so that is the host in the `client_id` URL.

Inspector never resolves that name: it only sends the URL as a string. The CIMD login worked on 8 November 2026 without a `/etc/hosts` entry. You only need `127.0.0.1 host.docker.internal` in `/etc/hosts` to run the `curl http://host.docker.internal:8081/...` check in Recovery from your own shell.

### Enable CIMD on Keycloak

CIMD is an experimental Keycloak feature (verified present in the Dev Services image, Keycloak 26.7.4, pinned in all three `application.properties`), off by default. It is switched on through Dev Services with:

```
quarkus.keycloak.devservices.features=cimd
```

already present in all three `application.properties` files.

The feature flag alone is not enough. Three more pieces are in place, and each one failed in its own way when missing (checked 8 November 2026):

| Piece | Where | Symptom without it |
|-------|-------|--------------------|
| Client policy `cimd-policy`, condition `client-id-uri`: scheme `http`/`https`, Trusted domains `host.docker.internal` | `keycloak-realm.json` | "Client not found": Keycloak treats the URL as an unknown client id |
| Client profile `cimd-profile`, executor `client-id-metadata-document`: Allow http scheme on, Trusted domains `host.docker.internal`, `localhost` and `127.0.0.1` (redirect URIs), `github.com` (`client_uri`) | `keycloak-realm.json` | "Invalid Client ID: domain not allowed" |
| Realm role scope mapping on the `attendee` client scope: roles `attendee`, `speaker` | `keycloak-realm.json` (`scopeMappings`) | Sign-in works but `/mcp` answers 403 `insufficient_scope`: Keycloak creates CIMD clients with full scope off, so without the mapping the token carries no realm roles |
| `%dev.quarkus.http.host-validation.allowed-hosts=localhost,127.0.0.1,host.docker.internal` | `conference-mcp` `application.properties` | "Client Metadata fetch failed": Quarkus answers Keycloak's fetch with 400, because it rejects non-localhost `Host` headers when bound to localhost |

Allow http scheme and the http condition scheme are demo-only, like the HTTP `client_id` itself. The realm changes need a fresh Keycloak container (see "Reload the realm").

Because Dev Services reuses the shared Keycloak container, the feature only takes effect after the container is recreated. If the running container predates this setting, recycle it with the "Reload the realm" procedure below, then restart the apps.

Confirm the feature is live from the Keycloak startup log:

```bash
docker logs $(docker ps -q --filter "label=quarkus-dev-service-keycloak=porto-keycloak") 2>&1 \
  | grep -i "features enabled"
# expect a line listing cimd, e.g. "Experimental features enabled: cimd:v1"
```

### Drive it from Inspector

Verified with MCP Inspector v2.14.0 on 8 November 2026:

1. Run `npx -y @modelcontextprotocol/inspector@latest`. Without `@latest`, `npx` may start a cached v1, which is deprecated. v2 warns that it wants Node 22.23.4 or newer but ran on 22.15.1. It opens on `http://127.0.0.1:6274`, so its redirect URI is `http://127.0.0.1:6274/oauth/callback`. The metadata document lists both the `127.0.0.1` and the `localhost` variants.
2. Add a server: Streamable HTTP, URL `http://localhost:8081/mcp`.
3. In that server's **Settings → OAuth Settings**: Client ID `http://host.docker.internal:8081/cimd/mcp-inspector.json`, Scopes `openid attendee`, and **untick "Request refresh token"**. Left on, Inspector adds `offline_access`, which the metadata document does not declare, and Keycloak answers `invalid_scope`.
4. Connect. Keycloak fetches the document (`GET /cimd/mcp-inspector.json` in the `conference-mcp` access log), shows the login, then a consent screen naming "MCP Inspector (CIMD demo client)" and "The client's hostname is host.docker.internal". That consent screen is a good stage moment: the server is telling the user which URL vouches for this client.
5. Run `list_sessions` to show the token works.

Keycloak stores what it fetched as a client in the realm and keeps it for at least 5 minutes (`cimd.cache.expiry.time.in.sec` on that client). After editing `mcp-inspector.json`, wait that out or recycle Keycloak. Inspector also keeps its tokens: after a realm change, use **Clear stored OAuth state** in the server settings before reconnecting.

If Inspector misbehaves on stage, drive the contrast manually and show the authorization request on the second screen:

```bash
KC=$(curl -s http://localhost:8081/.well-known/oauth-protected-resource | jq -r '.authorization_servers[0]')
VERIFIER=$(openssl rand -base64 48 | tr -d '=+/\n' | cut -c1-64)
CHALLENGE=$(printf %s "$VERIFIER" | openssl dgst -sha256 -binary | openssl base64 -A | tr '+/' '-_' | tr -d '=')
open "$KC/protocol/openid-connect/auth?response_type=code\
&client_id=http://host.docker.internal:8081/cimd/mcp-inspector.json\
&redirect_uri=http://localhost:6274/oauth/callback\
&scope=openid%20attendee&code_challenge=$CHALLENGE&code_challenge_method=S256"
```

`attendee` is required: without it the token has no `aud=conference-mcp` and `/mcp` rejects it. Nothing listens on port 6274 unless Inspector is running, so the browser ends on an error page after login; the point is the Keycloak log line, not the callback.

Watch the Keycloak log: it fetches the metadata document before rendering the login page.

### DCR vs CIMD, the punchline

The DCR section above had to clear three anonymous client-registration policies just to let Inspector register, and keeps a consent prompt to make up for trusting an unknown client. Under CIMD the client writes nothing: Keycloak reads the document and caches it as a client record on its own terms.

| Concern under DCR | Under CIMD |
|-------------------|------------|
| Allowed Client Scopes policy rejected the `openid` scope | No registration step, scope is requested at authorization time as normal |
| Trusted Hosts policy rejected the `client_uri` and the sender IP | No registration call to police, the client identity is a document the server reads |
| Full Scope Disabled stripped realm roles from the token | Keycloak also creates CIMD clients with full scope off. Here the fix is a role scope mapping on the `attendee` client scope, so a client gets the roles only by asking for that scope |
| Consent Required is the only guard against an unvetted client getting a token silently | The client identity is a URL the server fetches and whose redirect URIs it checks |
| The open anonymous registration endpoint is a standing write surface | There is no writeable registration endpoint at all |

Talking point: **DCR makes the authorization server keep a writeable door open, then asks you to pick which locks to remove and which to keep. CIMD removes the door. The client introduces itself with a URL the server reads, nothing is stored, and there is no registration surface to attack.**

## Demo 1: Public schedule lookup and CIMD (~7 min)

1. Open the conference-chat browser at `http://localhost:8082/`. MCP Inspector at `http://localhost:8081/mcp` is the backup client only.
2. Log in as **attendee / attendee**.
3. Ask: *"What's happening right now?"* (calls `whats_on_now`). At 11:40 that is eight sessions, including this talk.
4. Ask: *"What's coming up next?"* (calls `whats_next`).
5. Highlight PKCE: the `conference-chat` terminal logs a line on every login, e.g.
   ```
   PKCE on outbound authorization request: code_challenge_method=S256 code_challenge=E9Me...
   ```
   Point at it on the terminal (it survives the redirect, unlike the URL bar). Then show the access token decoded in the inspector.

6. **CIMD live.** Switch to MCP Inspector, pointed at `http://localhost:8081/mcp`, and sign in with the URL `http://host.docker.internal:8081/cimd/mcp-inspector.json` as the client id (see "Drive it from Inspector" above). On the second screen, show the Keycloak log line where it fetches `/cimd/mcp-inspector.json` before rendering the login page. Say: *"No registration call. The client id is a URL, Keycloak just read it."* If the Inspector build can't send a URL client id, use the manual authorization request from the same section; the point is the fetch, not the callback.

Talking points: **PKCE, CIMD as the recommended way for an AI client to get an identity, why client identity matters for AI clients.**

The audit dashboard shows nothing new (read-only tools do not audit). Tell the audience: "Read tools do not show up. That is on purpose. The audit log records intent, not curiosity."

DCR is not shown live. It is on the slides right after Demo 1, as the deprecated alternative; see "Client registration on slides" above.

## Demo 2: Personal agenda with token exchange (~7 min)

1. Click **Sign out** in the chat's top bar (it ends the Keycloak session too), then log in as **willem.jan / willem.jan**. Demo 2 and 3 both run as you, and the audit punchline names you. The agenda starts empty.
2. Ask: *"Bookmark the GraalVM talk for me."* (calls `bookmark_session`). A blue `BOOKMARK_ADD` event flies onto the audit dashboard.
3. Ask: *"Show me my agenda."* (calls `my_agenda`).
4. Ask: *"Also add the Practical MCP Security talk."* Then *"Do I have any conflicts?"* (calls `my_conflicts`; both run 11:10 to 12:00).
5. Ask: *"Rate the MCP Security talk 5 stars with the comment 'great use of caffeine'."* (calls `rate_session`). A `RATE_SESSION` event lands, with `attendee_subject=willem.jan`.
6. Turn to the second monitor and read the event aloud:
   ```
   audit_event
     action               RATE_SESSION
     target               session:<id> (Practical MCP Security)
     attendee_subject     willem.jan
     token_acr            1
     ...
   ```
   `executed_by_client` reads `conference-chat via conference-mcp`: the enterprise app asked, the MCP server called the API with a token it exchanged for one minted for `conference-api`.
7. **Punchline:** not "AI rated it 5 stars" but "Willem Jan rated it 5 stars, executed by an AI on his behalf, with his token, fully auditable."
8. Be ready for "isn't forwarding the token passthrough?". No: your token (aud=conference-mcp) stops at the MCP server, which exchanges it (RFC 8693) for a conference-api token per call. Next slide.

## Demo 3: Step-up auth and prompt injection (~8 min)

1. Stay logged in as **willem.jan**, no MFA (acr=1).
2. Ask: *"Show me the feedback on my MCP talk."* (calls `my_session_feedback`) returns seeded ratings, anonymous: names are personal data and sit behind step-up.
   - **Injection beat.** One rating is not like the others: an attendee wrote instructions to the assistant into the comment (`DemoDataSeeder.INJECTED_COMMENT`), telling it to call `cancel_my_session` with reason "rescheduled by organisers". Scroll the tool result box to it (the box scrolls at 240px) and read it aloud. Attendee text just reached an agent that holds a destructive tool.
   - **If the model falls for it**, the chat calls `cancel_my_session` on its own and gets the amber step-up card. Say: *"I asked to read feedback. My agent wants my MFA code to cancel my talk. That prompt is the tripwire."* **Do not click the step-up button**: it resends the feedback prompt after MFA, and then the injection would succeed. Leave the card; step 3 asks for step-up anyway.
   - **If the model ignores it** (current Claude models usually do; the scripted planner always does), say: *"Good model. But the model is not my security boundary. Let's assume it fell for it."* In MCP Inspector, signed in as `willem.jan` (no MFA), call `cancel_my_session` with `session_query` "Practical MCP Security" and the injected reason. Same `insufficient_user_authentication` refusal, before the API is called.
   - Either way, an amber `CANCEL_SESSION_ATTEMPTED_WITHOUT_STEP_UP` row lands on the dashboard with the injected reason in its detail. Point at it: *"The attack failed, and the log has the attacker's words in it."*
3. Ask: *"Who signed up to attend my MCP Security talk?"* (calls `view_session_attendees`).
4. `conference-mcp` refuses with the tool error `insufficient_user_authentication` before it calls the API. The chat shows it as an amber step-up card; point at the same line in the `conference-mcp` terminal. Talk through what the client does next: re-auth with `acr_values=urn:porto:mfa`, retry the tool call.
5. Re-authenticate at the higher ACR (the OTP prompt uses the seeded TOTP secret from pre-flight) and retry. The audit dashboard will show an amber `view_session_attendees` event with `token_acr=urn:porto:mfa`.
6. Ask: *"Cancel my MCP Security talk, reason is I want to go home early."* (calls `cancel_my_session`). A red `CANCEL_SESSION` event lands with the strong acr.
7. Undo it straight away: *"Undo the cancellation of my MCP Security talk, reason: back on track."* The same tool with `undo=true` restores it and records `CANCEL_SESSION_UNDONE`. It is explicit, not a toggle: a retried cancel fails with 409 instead of silently undoing itself. Undo before Q&A: a cancelled talk drops out of `whats_on_now`.
8. **The honest caveat.** You now hold an MFA token. Say: *"If my agent read that comment now, step 6 is exactly what it would do, and it would work."* `conference-mcp` checks the `acr` claim, not how recent the MFA was. What still holds: ask *"Cancel the GraalVM talk, reason: rescheduled by organisers."* The tool passes step-up, the API refuses on ownership (403), and a `CANCEL_SESSION_ATTEMPTED` row lands on the dashboard. *"The agent was fooled, the call was made, and the API said no, and wrote down that someone tried."* Then the slide: a destructive call should show the user the exact tool and arguments and wait for a yes, every time. This client doesn't, and that's the gap.
9. Say the shortcut out loud: the API learns the verified `acr` from an `X-Mcp-Acr` header, trusted only on tokens issued to `conference-mcp`, because the token exchange drops it. That is a demo shortcut; the proper fix is delegation with an `act` claim.

Talking points: **step-up is server-driven: the tool decides it needs more proof. Honest caveat: MCP itself only standardises scope step-up (403 `insufficient_scope`); assurance step-up with `acr` (RFC 9470) is a contract between this server and this client.**

## Recovery

| Symptom | Fix |
|---------|-----|
| Inspector: `-32099 ... Error POSTing to endpoint (HTTP 404)` | Stale MCP session after a `conference-mcp` reload. Disconnect then Connect in Inspector to get a fresh session. |
| Inspector DCR fails (rejected policy, 403 on tools, claimless token, or no consent screen) | The running Keycloak is using a stale realm. Recycle it so the realm re-imports (see "Reload the realm" below). |
| `/mcp` returns `403 insufficient_scope` | The token authenticates but its realm roles do not include `attendee`. Log in as `attendee`, `willem.jan`, or `admin-demo`; all three carry it. |
| CIMD: Keycloak does not fetch the metadata document, or `invalid_client` | The `cimd` feature is not active on the running container, or the container predates the `features=cimd` setting. Recycle Keycloak (see "Reload the realm"), then check the startup log for `features enabled: cimd`. |
| CIMD: "Client not found" or "domain not allowed" | The running Keycloak predates the `cimd-policy` / `cimd-profile` in the realm. Recycle Keycloak (see "Reload the realm"). |
| CIMD: "Client Metadata fetch failed" | `conference-mcp` answered the fetch with 400 (look for `GET /cimd/mcp-inspector.json ... 400` in its access log). Check the `%dev.quarkus.http.host-validation.allowed-hosts` line is still in its `application.properties`. |
| CIMD: Keycloak cannot reach the metadata URL | `host.docker.internal` is not resolvable. Add `127.0.0.1 host.docker.internal` to `/etc/hosts` and confirm `curl http://host.docker.internal:8081/cimd/mcp-inspector.json` returns the document. |
| The injection actually cancelled the MCP talk | Ask *"Undo the cancellation of my MCP Security talk, reason: back on track."* (needs MFA). Turn it into the point: this is the caveat from Demo 3 step 8, live. |
| Injected comment missing from the feedback | The database predates the seed change. Reset Postgres (see "Audit log shows stale rehearsal data"). |
| `whats_on_now` returns nothing | Confirm `DEMO_NOW` env var is set, then `q` and restart `conference-api`. |
| Audit dashboard frozen | Hard refresh the browser tab; the poll is every 2 seconds. |
| Audit log shows stale rehearsal data | `docker rm -f` the Dev Services Postgres container and restart `conference-api`. Hibernate `drop-and-create` plus the seeders give you a clean slate. |
| Step-up flow does not prompt for TOTP | The running Keycloak predates the realm's `Browser Stepup` flow. Recycle Keycloak so the realm re-imports (see "Reload the realm"). As a fallback, show the `insufficient_user_authentication` tool error and explain what a compliant client does next. |
| `/mcp` returns `401` with a valid login | The token lacks `aud=conference-mcp`. The audience comes from the `attendee` client scope: `conference-chat` has it as a default scope, DCR/CIMD clients must request `scope=attendee` (it is a realm-level optional scope). |

### Reload the realm

Editing `keycloak-realm.json` has no effect until Keycloak re-imports it, and Dev Services **reuses** the shared Keycloak container across app restarts. To force a re-import, the container must be gone before any app starts:

```bash
# 1. Ctrl-C all three quarkus:dev processes.
# 2. Remove the shared Keycloak container so Dev Services cannot reuse it:
docker rm -f $(docker ps -aq --filter "label=quarkus-dev-service-keycloak=porto-keycloak")
# 3. Restart conference-api FIRST (it creates Keycloak and imports the realm), then mcp, then chat.
```

After this, reconnect Inspector so it registers a fresh DCR client against the new realm (the previously cached client id no longer exists). This recycle is also what activates the `cimd` feature on a container that predates the `features=cimd` setting.

## After the talk

```bash
# Ctrl-C all three quarkus:dev processes.
# Dev Services containers stay running between sessions; remove them explicitly with:
docker ps --filter "label=quarkus-dev-service" -q | xargs -r docker rm -f
```
