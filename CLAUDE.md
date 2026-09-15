# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A Spring Boot 4 / Java 25 MCP server that exposes two tools (`search_jobs`, `get_user_info`) to authenticated MCP
clients, backed by a separate "Jobshunter" internal API. The defining architectural trait is that **the server acts
as its own internal OAuth2 authorization server** (`MCP_INTERNAL_AS`) — it does not delegate token issuance to
Google or any third party. Google is used only as an identity broker during login.

## Commands

```bash
mvn spring-boot:run                              # run locally on :8081 (needs env vars, see below)
mvn test                                          # Surefire: runs *Test classes only
mvn verify                                        # Surefire + Failsafe: also runs *IT classes (integration)
mvn verify -Dit.test=McpSecurityFilterChainIT     # run a single *IT integration test
mvn test -Dtest=JobSearchToolTest                 # run a single *Test unit test
```

- `*Test` classes run in the `test` phase (Surefire). Some of these (e.g. `SearchJobsMcpInternalAsTest`) are full
  `@SpringBootTest` scenarios using MockWebServer to stand in for the Jobshunter backend, but are still named `*Test`
  and run under `mvn test`.
- `*IT` classes run in `integration-test`/`verify` (Failsafe) — e.g. `McpSecurityFilterChainIT`.
- Local run requires exporting env vars manually (`.env` is a convention only; Spring does not auto-load it): see
  `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `MCP_AS_ISSUER`, `MCP_AS_MCP_AUDIENCE`, `MCP_AS_JOBSHUNTER_AUDIENCE`,
  `JOBSHUNTER_BASE_URL`, `JOBSHUNTER_TRUST_STORE_PASSWORD` (full list in README's Configuration reference).
- Docker: `docker compose up --build` (host port `9002` -> container `8081`); requires
  `MCP_AS_SIGNING_KEY_PEM` to be set (fails fast otherwise, to avoid JWKS/token rotation across restarts).

## Architecture

Two curated diagrams exist and should be kept in sync with code changes to auth/tool flows:
`architecture/MCP_Server_Authentication_Initialization_Flow.md` and `architecture/MCP_Tool_Invocation_Flow.md`
(each has a companion `.mmd`/`.pdf`).

### Token model — three distinct JWTs, easy to confuse

1. **Google `id_token`** — identity proof obtained during the OAuth broker flow. Verified by
   `GoogleIdTokenValidator` against `googleIdTokenDecoder` (issuer `accounts.google.com`, audience = Google client
   id). Never reused as an MCP access token.
2. **MCP access token** — minted by this server (`McpTokenIssuer.issueMcpAccessToken`) after validating the Google
   id_token. Claims: `aud=<MCP_AS_MCP_AUDIENCE>`, `token_use=mcp_access`, default TTL 15m. Required bearer token for
   `POST /mcp`. Validated via the `mcpJwtDecoder` bean in `McpSecurityConfig`, which chains issuer + audience +
   `token_use` validators (`DelegatingOAuth2TokenValidator`).
3. **Delegated Jobshunter token** — minted server-side per tool call (`McpTokenIssuer.issueDelegatedJobshunterToken`)
   from the caller's already-authenticated MCP JWT. Claims: `aud=<MCP_AS_JOBSHUNTER_AUDIENCE>`,
   `token_use=jobshunter_delegated`, default TTL 5m. This is what actually gets sent to the Jobshunter backend —
   **the original MCP client token is never forwarded downstream.** Resolution goes through the
   `DelegatedTokenResolver` interface (`InternalJwtDelegatedTokenResolver` impl, wired in
   `DelegatedTokenResolverConfiguration`) so the mechanism is swappable.

All MCP-issued tokens are signed by `McpJwtSigningService` using an RSA key: either loaded from
`MCP_AS_SIGNING_KEY_PEM` (PKCS#8) or generated ephemerally at startup if absent (fine for local dev, disallowed in
docker-compose — see above). The public key is exposed at `/.well-known/jwks.json` via `McpWellKnownController`, and
`key-id` (`kid`) is attached to all signed tokens to support rotation.

### Request flow (OAuth broker + tool call)

1. Client discovers metadata: `/.well-known/oauth-authorization-server`, `/.well-known/oauth-protected-resource`,
   `/.well-known/jwks.json` (all built from the configured `issuer`, never derived from request headers — this
   matters when running behind ngrok/reverse proxies).
2. `GET /authorize` (`McpOAuthController`) — validates the request via `OAuthRequestValidator` (redirect_uri
   allowlist, strict unknown-parameter rejection, PKCE S256 required), then 302-redirects to Google's real authorize
   endpoint, substituting this server's own `client_id`.
3. `POST /token` — validates via `OAuthRequestValidator`, exchanges the code with Google server-to-server (injecting
   `client_id`/`client_secret`), validates the returned Google `id_token`, then **replaces** Google's access token
   with an MCP-minted access token in the response body. The client only ever holds MCP tokens after this point.
4. `POST /mcp` — Spring Security resource-server filter chain validates the MCP JWT (see token model above), then
   Spring AI MCP (`STREAMABLE`, `SYNC`) dispatches `tools/call` to `JobSearchTool`.
5. Inside a tool method, `resolveUserToken` pulls the authenticated `JwtAuthenticationToken` off
   `SecurityContextHolder`, mints a delegated token, and `JobshunterClient` calls the Jobshunter internal API with
   it over a dedicated `RestClient` (TLS trust store configured via `JobshunterProperties.Ssl`, used for ngrok/dev
   endpoints — see `RestClientConfig`).

### Security filter chains (`McpSecurityConfig`)

Three `@Order`ed chains, evaluated in order — get this order wrong and either public endpoints 401 or `/mcp` becomes
unauthenticated:
1. `/mcp`, `/mcp/**` — stateless, JWT resource-server auth via `mcpJwtDecoder`, `anyRequest().authenticated()`.
2. The public well-known + `/authorize` + `/token` endpoints — explicit per-path `permitAll()`, everything else on
   this matcher `denyAll()`.
3. Fallback matcher — `anyRequest().denyAll()` for anything not covered above (fail-closed by default).

### OAuth hardening (`OAuthRequestValidator`, `OAuthLogSanitizer`)

- `redirect_uri` must be in the configured allowlist (`mcp.oauth.allowed-redirect-uris`), unless it's a loopback
  address restricted to `allowed-loopback-redirect-paths` (native/desktop client support).
- Unknown query/form parameters on `/authorize` and `/token` are rejected unless present in
  `additional-authorize-parameters` / `additional-token-parameters` (controlled compatibility surface, e.g. for
  clients that send `audience`/`resource`).
- All OAuth request/response logging goes through `OAuthLogSanitizer` to redact secrets/tokens before they hit logs
  — don't `log.info` raw params/bodies in this controller; extend the sanitizer instead.

### Errors and correlation IDs

- `JobshunterApiException` carries a machine-readable `ErrorCode` (`AUTH_FAILED`, `TIMEOUT`, `UPSTREAM_UNAVAILABLE`,
  `VALIDATION`, `UNKNOWN`) so MCP clients can branch without string-matching messages. `JobshunterClient.execute`
  maps HTTP status codes and `ResourceAccessException` causes (timeout vs. protocol mismatch vs. unreachable) into
  the right `ErrorCode`.
- `RequestContext` generates a UUID request id per `tools/call`, puts it in MDC (logged automatically) and forwards
  it to Jobshunter as `X-Request-Id` — this is the correlation id to grep for across MCP and Jobshunter logs.
- Logging uses a custom Logback pattern converter (`LevelIconConverter`, registered as `%levelIcon` in
  `logback-spring.xml`) that renders a Unicode glyph per level (✖/⚠/ℹ/⚙/»). Preserve this when touching logging
  config.

### Adding a new MCP tool

Most tools follow the `JobSearchTool` pattern: a `@Service` with `@Tool`-annotated methods (Spring AI), registered
via a `MethodToolCallbackProvider` bean in `McpToolConfiguration` (add the new tool object to `.toolObjects(...)`).
Inside the method, resolve the delegated token the same way (`SecurityContextHolder` → `JwtAuthenticationToken` →
`DelegatedTokenResolver`) rather than reusing the caller's MCP token against Jobshunter.

A tool that needs to send MCP progress notifications (`notifications/progress`) — e.g. `SearchJobsTool`'s
`wait_for_search` — instead uses `@McpTool`/`@McpToolParam` (`org.springframework.ai.mcp.annotation`) with an
`McpSyncRequestContext` (`org.springframework.ai.mcp.annotation.context`) method parameter and calls
`context.progress(...)`. These are auto-scanned by `spring-ai-mcp-annotations`' autoconfiguration (`@McpTool`
methods on any Spring bean become tool specifications automatically) and merged with the `@Tool`-based tools into
the same `McpSyncServer` — no registration in `McpToolConfiguration` needed. Reach for this only when a tool
genuinely needs the request/exchange context (progress, sampling, elicitation) that plain `@Tool` methods can't get
via `ToolContext`/`McpToolUtils.getMcpExchange` alone. A class exposing `@McpTool` methods must have exactly one
constructor Spring can resolve unambiguously (use `@Value` with a default for any non-bean parameter, e.g. a poll
interval) — multiple constructors without `@Autowired` fail bean creation with "No default constructor found".

## Reference

- Config keys and defaults: `src/main/resources/application.yml` (heavily commented — read the comments before
  changing a value, they explain the security rationale for TTLs/audiences being kept separate).
