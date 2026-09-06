# jobshunter-mcp-server

Spring Boot MCP server exposing `search_jobs` and `get_user_info` tools.

## Token model

The server supports two delegation modes:

- `GOOGLE_PASSTHROUGH` (rollback mode): legacy behavior where the upstream Google token payload is proxied and normalized.
- `MCP_INTERNAL_AS` (target mode): MCP acts as a first-party Authorization Server.

In `MCP_INTERNAL_AS`, token responsibilities are separated:

- MCP access token for `/mcp`: `aud=<mcp audience>`, `token_use=mcp_access`
- Delegated Jobshunter token for internal API calls: `aud=<jobshunter audience>`, `token_use=<jobshunter delegated token use>`

No standard path rewrites `id_token` into `access_token` in internal-AS mode.

The OAuth bridge uses a broker model:

- MCP publishes local AS metadata and local JWKS for MCP-issued tokens.
- `GET /authorize` enforces strict public-client authorization request validation before redirecting upstream.
- `POST /token` accepts public client calls (`token_endpoint_auth_methods_supported=["none"]`) with strict grant-specific validation.
- MCP performs confidential server-side token exchange to Google using configured `client_id` and `client_secret`.
- Google `id_token` is identity proof for MCP minting and is returned as upstream evidence; it is not the MCP access token.

## Runtime stack

- Java 25
- Spring Boot 4
- Spring AI MCP Server (WebMVC, Streamable HTTP)

## Endpoints

- MCP endpoint: `POST /mcp`
- OAuth discovery:
  - `GET /.well-known/oauth-protected-resource`
  - `GET /.well-known/oauth-authorization-server`
- OAuth bridge:
  - `GET /authorize` (validates `response_type=code`, PKCE `code_challenge_method=S256`, required `state`/`redirect_uri`, and redirect allowlist, then redirects to Google)
  - `POST /token` (validates `grant_type=authorization_code`, required parameters and redirect allowlist, then exchanges code and returns MCP access token in internal-AS mode)
- MCP JWKS:
  - `GET /.well-known/jwks.json`

## Security policy

- Public endpoints are explicitly allowlisted:
  - `GET /.well-known/oauth-protected-resource`
  - `GET /.well-known/oauth-authorization-server`
  - `GET /.well-known/jwks.json`
  - `GET /authorize`
  - `POST /token`
- `POST /mcp` requires a valid MCP JWT (missing or invalid token returns `401`).
- Any endpoint outside this allowlist is denied by default (`403`).

## Configuration

### Local development with `.env`

Keep runtime values in a local `.env` file (already ignored by git via `.gitignore`) and avoid committing secrets or environment-specific URLs in repo defaults.

Minimal `.env` example:

```dotenv
GOOGLE_CLIENT_ID=your-google-client-id
GOOGLE_CLIENT_SECRET=your-google-client-secret
MCP_AS_ISSUER=https://your-public-issuer.example
MCP_AS_MCP_AUDIENCE=mcp-api
MCP_AS_JOBSHUNTER_AUDIENCE=jobshunter-internal-api
JOBSHUNTER_BASE_URL=https://your-jobshunter.example
JOBSHUNTER_TRUST_STORE_PASSWORD=your-trust-store-password
```

### Required (all modes)

- `GOOGLE_CLIENT_ID`
- `GOOGLE_CLIENT_SECRET`
- `JOBSHUNTER_BASE_URL`
- `JOBSHUNTER_TRUST_STORE_PASSWORD` (required when `jobshunter.ssl.trust-store` is configured)

### Internal-AS mode (`MCP_DELEGATION_MODE=MCP_INTERNAL_AS`)

- `MCP_AS_ISSUER` (canonical public issuer/base URL used for MCP-minted JWT `iss` and OAuth discovery metadata)
- `MCP_AS_MCP_AUDIENCE` (audience accepted on `/mcp`)
- `MCP_AS_JOBSHUNTER_AUDIENCE` (audience expected by Jobshunter for delegated JWT)
- `MCP_AS_SIGNING_KEY_PEM` (optional PKCS#8 RSA private key PEM; if omitted, ephemeral key is generated at startup)
- `MCP_AS_KEY_ID` (defaults to `mcp-key-1`)
- `MCP_AS_MCP_ACCESS_TOKEN_USE` (default `mcp_access`)

Token timings are intentionally fixed in application configuration for simplicity:
- MCP access token TTL: `15m`
- Jobshunter delegated token TTL: `5m`
- Jobshunter HTTP timeouts: connect `5s`, response `25m`

OAuth discovery hardening:
- `/.well-known/oauth-authorization-server` and `/.well-known/oauth-protected-resource` are built from `MCP_AS_ISSUER`.
- Discovery metadata is not derived from `Host` or `X-Forwarded-*` request headers.
- Metadata includes extension fields that describe broker mode and upstream OAuth endpoints.

OAuth request hardening:
- `MCP_OAUTH_ENFORCE_REDIRECT_ALLOWLIST` (default `true`) enables strict `redirect_uri` allowlisting on both `/authorize` and `/token`.
- `MCP_OAUTH_REDIRECT_URI_1` (and additional indexed values) define allowed redirect URIs used by controlled clients.
- `MCP_OAUTH_ALLOW_LOOPBACK_REDIRECT_URIS` (default `true`) permits loopback redirects (`localhost`/`127.0.0.1`) for native clients using dynamic ports.
- `mcp.oauth.allowed-loopback-redirect-paths` restricts loopback redirects to approved callback paths (default `/callback`).
- `MCP_OAUTH_REJECT_UNKNOWN_AUTHORIZE_PARAMS` and `MCP_OAUTH_REJECT_UNKNOWN_TOKEN_PARAMS` (both default `true`) reject unknown request parameters.
- `mcp.oauth.additional-token-parameters` can explicitly allow vetted extension parameters (default includes `audience`, `resource`, `client_id`, and `scope`).

### Rollback mode (`MCP_DELEGATION_MODE=GOOGLE_PASSTHROUGH`)

- `MCP_GOOGLE_AUDIENCE` / `GOOGLE_CLIENT_ID` for `/mcp` audience validation
- `GOOGLE_ISSUER_URI` (default `https://accounts.google.com`)

### Fail-fast behavior

The server is intentionally fail-fast for critical configuration:

- Spring `@ConfigurationProperties` + validation (`@NotBlank`) stop startup when required values are missing.
- `jobshunter.ssl.trust-store-password` is mandatory when a trust store is configured.
- Docker Compose uses `${VAR:?VAR is required}` for critical env vars, so container startup fails immediately when they are absent.

## Jobshunter trust configuration (internal-AS)

Configure Jobshunter to validate delegated JWTs against MCP trust material:

- `DELEGATED_AUTH_ISSUER_URI=<MCP_AS_ISSUER>`
- `DELEGATED_AUTH_AUDIENCE=<MCP_AS_JOBSHUNTER_AUDIENCE>`
- `DELEGATED_AUTH_JWKS_URI=<MCP_AS_ISSUER>/.well-known/jwks.json`

## Run locally

```bash
mvn spring-boot:run
```

## Test

```bash
mvn test
```

## Rollout strategy

1. Deploy with `MCP_DELEGATION_MODE=GOOGLE_PASSTHROUGH`.
2. Enable `MCP_INTERNAL_AS` in dev and validate Claude, Postman, and Jobshunter.
3. Roll out gradually (canary) with auth/error monitoring.
4. Cut over production to `MCP_INTERNAL_AS`.