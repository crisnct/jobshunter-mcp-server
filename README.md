# jobshunter-mcp-server

Standalone Spring Boot MCP server exposing one tool: `search_jobs`.

The tool calls Jobshunter synchronously using pass-through authentication (Google OAuth2 `id_token` validated on `/mcp`, then forwarded to Jobshunter internal API) and returns the same `jobsFound` payload.

## Runtime stack

- Java 25
- Spring Boot 4
- Spring AI MCP Server (WebMVC, Streamable HTTP)

## MCP endpoint

- URL: `http://localhost:8081/mcp`
- Protocol: `STREAMABLE`
- Tool enabled: `search_jobs`
- Authentication: `Authorization: Bearer <google-id-token>`

## Required environment variables

- `MCP_GOOGLE_AUDIENCE` or `GOOGLE_CLIENT_ID` - audience accepted by MCP JWT validation
- `DELEGATED_AUTH_AUDIENCE` in Jobshunter app should match the same audience used to mint client ID tokens

## Optional environment variables

- `JOBSHUNTER_BASE_URL` (default: `https://localhost:8443`)
- `JOBSHUNTER_SEARCH_JOBS_PATH` (default: `/api/internal/search_jobs`)
- `JOBSHUNTER_CONNECT_TIMEOUT` (default: `5s`)
- `JOBSHUNTER_RESPONSE_TIMEOUT` (default: `5m`)
- `MCP_REQUIRED_SCOPE` (optional; empty by default)
- `GOOGLE_ISSUER_URI` (default: `https://accounts.google.com`)
- `JOBSHUNTER_TRUST_STORE` (optional path/resource for local self-signed TLS)
- `JOBSHUNTER_TRUST_STORE_PASSWORD` (required when trust store is configured)
- `JOBSHUNTER_TRUST_STORE_TYPE` (default: `PKCS12`)

## Run locally

```bash
mvn spring-boot:run
```

## Test

```bash
mvn test
```

## Security note

`/mcp` is protected with Google JWT validation (issuer + audience by default; scope enforcement is optional via `MCP_REQUIRED_SCOPE`).
The MCP server never sends browser-specific credentials (`device_id`, CSRF) to Jobshunter and forwards the authenticated user JWT to the internal Jobshunter endpoint.

## Pass-through checklist

1. Configure your Google OAuth application so MCP clients can obtain Google ID tokens.
2. Set `MCP_GOOGLE_AUDIENCE` (or `GOOGLE_CLIENT_ID`) to the audience claim accepted by MCP.
3. Configure Jobshunter `DELEGATED_AUTH_ISSUER_URI` and `DELEGATED_AUTH_AUDIENCE` to validate the same token.
4. Keep `DELEGATED_AUTH_REQUIRED_SCOPE` empty unless your ID token actually contains a compatible scope claim.