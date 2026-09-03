# jobshunter-mcp-server

Spring Boot MCP server exposing `search_jobs` and `get_user_info` tools.

## Token model

The server supports two delegation modes:

- `GOOGLE_PASSTHROUGH` (rollback mode): legacy behavior where the upstream Google token payload is proxied and normalized.
- `MCP_INTERNAL_AS` (target mode): MCP acts as a first-party Authorization Server.

In `MCP_INTERNAL_AS`, token responsibilities are separated:

- MCP access token for `/mcp`: `aud=<mcp audience>`
- Delegated Jobshunter token for internal API calls: `aud=<jobshunter audience>`

No standard path rewrites `id_token` into `access_token` in internal-AS mode.

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
  - `GET /authorize` (redirects to Google)
  - `POST /token` (exchanges Google code and returns MCP access token in internal-AS mode)
- MCP JWKS:
  - `GET /.well-known/jwks.json`

## Configuration

### Required (all modes)

- `MCP_OAUTH_CLIENT_ID`
- `MCP_OAUTH_CLIENT_SECRET`
- `JOBSHUNTER_BASE_URL`

### Internal-AS mode (`MCP_DELEGATION_MODE=MCP_INTERNAL_AS`)

- `MCP_AS_ISSUER` (issuer used in MCP-minted JWTs)
- `MCP_AS_MCP_AUDIENCE` (audience accepted on `/mcp`)
- `MCP_AS_JOBSHUNTER_AUDIENCE` (audience expected by Jobshunter for delegated JWT)
- `MCP_AS_SIGNING_KEY_PEM` (optional PKCS#8 RSA private key PEM; if omitted, ephemeral key is generated at startup)
- `MCP_AS_KEY_ID` (defaults to `mcp-key-1`)
- `MCP_AS_ACCESS_TOKEN_TTL` (default `15m`)
- `MCP_AS_DELEGATED_TOKEN_TTL` (default `5m`)

### Rollback mode (`MCP_DELEGATION_MODE=GOOGLE_PASSTHROUGH`)

- `MCP_GOOGLE_AUDIENCE` / `MCP_OAUTH_CLIENT_ID` for `/mcp` audience validation
- `GOOGLE_ISSUER_URI` (default `https://accounts.google.com`)

## Jobshunter trust configuration (internal-AS)

Configure Jobshunter to validate delegated JWTs against MCP trust material:

- `DELEGATED_AUTH_ISSUER_URI=<MCP_AS_ISSUER>`
- `DELEGATED_AUTH_AUDIENCE=<MCP_AS_JOBSHUNTER_AUDIENCE>`
- `DELEGATED_AUTH_JWKS_URI=<MCP_BASE_URL>/.well-known/jwks.json`

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