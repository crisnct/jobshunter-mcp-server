# jobshunter-mcp-server

Standalone Spring Boot MCP server exposing one tool: `search_jobs`.

The tool calls Jobshunter synchronously via delegated authentication (Google OAuth2 user token -> Google STS token exchange -> Jobshunter internal API) and returns the same `jobsFound` payload.

## Runtime stack

- Java 25
- Spring Boot 4
- Spring AI MCP Server (WebMVC, Streamable HTTP)

## MCP endpoint

- URL: `http://localhost:8081/mcp`
- Protocol: `STREAMABLE`
- Tool enabled: `search_jobs`
- Authentication: `Authorization: Bearer <google-access-token>`

## Required environment variables

- `MCP_GOOGLE_AUDIENCE` or `GOOGLE_CLIENT_ID` - audience accepted by MCP JWT validation
- `JOBSHUNTER_TOKEN_AUDIENCE` - audience requested for exchanged downstream token (default `jobshunter-internal`)

## Optional environment variables

- `JOBSHUNTER_BASE_URL` (default: `https://localhost:8444`)
- `JOBSHUNTER_SEARCH_JOBS_PATH` (default: `/api/internal/search_jobs`)
- `JOBSHUNTER_CONNECT_TIMEOUT` (default: `5s`)
- `JOBSHUNTER_RESPONSE_TIMEOUT` (default: `5m`)
- `MCP_REQUIRED_SCOPE` (default: `profile`)
- `GOOGLE_ISSUER_URI` (default: `https://accounts.google.com`)
- `GOOGLE_STS_URL` (default: `https://sts.googleapis.com/v1/token`)
- `JOBSHUNTER_TOKEN_SCOPES` (default: `search_jobs.execute`)
- `TOKEN_EXCHANGE_TIMEOUT` (default: `10s`)
- `TOKEN_EXCHANGE_GRANT_TYPE` (default: `urn:ietf:params:oauth:grant-type:token-exchange`)
- `TOKEN_EXCHANGE_SUBJECT_TOKEN_TYPE` (default: `urn:ietf:params:oauth:token-type:access_token`)
- `TOKEN_EXCHANGE_REQUESTED_TOKEN_TYPE` (default: `urn:ietf:params:oauth:token-type:access_token`)
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

`/mcp` is protected with Google JWT validation (issuer, audience, and scope).
The MCP server never sends browser-specific credentials (`device_id`, CSRF) to Jobshunter and relies on exchanged delegated tokens with a constrained audience and scope.

## IAM / STS setup checklist

1. Configure your Google OAuth application so MCP clients can obtain Google access tokens.
2. Set `MCP_GOOGLE_AUDIENCE` (or `GOOGLE_CLIENT_ID`) to the audience claim accepted by MCP.
3. Configure Google STS to allow token exchange for your MCP caller identity.
4. Set exchanged token audience to `JOBSHUNTER_TOKEN_AUDIENCE` (default `jobshunter-internal`).
5. Keep exchanged scopes minimal (default `search_jobs.execute`).
6. Configure Jobshunter internal endpoint to validate issuer, signature, audience, and scope.