# JobsHunter MCP Server

![Java](https://img.shields.io/badge/Java-25-orange)
![SpringBoot](https://img.shields.io/badge/Spring_Boot-4.0.0-6DB33F)
![MCP](https://img.shields.io/badge/MCP-Streamable_HTTP-1f6feb)
![Auth](https://img.shields.io/badge/OAuth2-PKCE_S256-8a2be2)
![License](https://img.shields.io/badge/License-BSL_1.1-lightgrey)

Spring Boot MCP server that exposes two tools for authenticated users: `search_jobs` and `get_user_info`.

The project is designed for clear security boundaries: MCP authenticates clients on `/mcp`, then mints a short-lived delegated token for internal Jobshunter API calls.

`✨` Clear onboarding. `🔒` Strong token boundaries. `⚡` Fast MCP tool access.

---

## Table of contents

- [Overview](#-overview)
- [Quick start](#-quick-start)
- [Connect an MCP client](#-connect-an-mcp-client)
- [Tools](#-tools)
- [Architecture](#-architecture)
- [Authentication model](#-authentication-model)
- [Endpoints](#-endpoints)
- [Security policy](#-security-policy)
- [Configuration reference](#-configuration-reference)
- [Docker deployment](#-docker-deployment)
- [Testing](#-testing)
- [Troubleshooting](#-troubleshooting)
- [Jobshunter trust configuration](#-jobshunter-trust-configuration)
- [Related docs](#-related-docs)
- [License](#-license)

---

## Overview

### What this server does

- `⚡` Exposes MCP endpoint `POST /mcp` using Spring AI MCP Server (`STREAMABLE`, `SYNC`).
- Provides two MCP tools for the authenticated user journey:
  - `🔎 search_jobs`: synchronous job search orchestration.
  - `👤 get_user_info`: profile retrieval from Jobshunter internal API.
- `🔐` Brokers OAuth with Google and mints MCP-owned JWTs for `/mcp`.
- `↔️` Calls Jobshunter with a delegated JWT (`aud` scoped for internal API), not with the MCP client token.

### Runtime stack

- Java 25
- Spring Boot 4
- Spring AI MCP Server (WebMVC, Streamable HTTP)

---

## Quick start

### 1) Prerequisites

- `☕` Java 25
- `🛠️` Maven
- `🔑` Google OAuth app credentials
- `🌐` Reachable Jobshunter backend URL

### 2) Configure environment

Create a local `.env` file (already ignored by git):

```dotenv
GOOGLE_CLIENT_ID=your-google-client-id
GOOGLE_CLIENT_SECRET=your-google-client-secret
MCP_AS_ISSUER=https://your-public-issuer.example
MCP_AS_MCP_AUDIENCE=mcp-api
MCP_AS_JOBSHUNTER_AUDIENCE=jobshunter-internal-api
MCP_AS_SIGNING_KEY_PEM=-----BEGIN PRIVATE KEY-----...-----END PRIVATE KEY-----
MCP_AS_KEY_ID=mcp-key-1
JOBSHUNTER_BASE_URL=https://your-jobshunter.example
JOBSHUNTER_TRUST_STORE_PASSWORD=your-trust-store-password
```

> [!IMPORTANT]
> `.env` is a local convention. Spring does not auto-load it by default. Export these variables in your shell or IDE run configuration before starting the app.

### 3) Run locally

```bash
mvn spring-boot:run
```

Server URL: `http://localhost:8081`

### 4) Verify discovery endpoints

```bash
curl http://localhost:8081/.well-known/oauth-authorization-server
curl http://localhost:8081/.well-known/oauth-protected-resource
curl http://localhost:8081/.well-known/jwks.json
```

---

## Connect an MCP client

Use MCP server URL:

- `http://localhost:8081/mcp` (local run)
- `http://localhost:9002/mcp` (docker-compose host port)

Supported preconfigured OAuth callback URIs:

| Client | Redirect URI |
|---|---|
| Local native loopback | `http://127.0.0.1:8787/callback` |
| Postman | `https://oauth.pstmn.io/v1/callback` |
| Claude | `https://claude.ai/api/mcp/auth_callback` |
| Cursor Agents | `https://www.cursor.com/agents/mcp/oauth/callback` |
| OpenAI Chat | `https://chat.openai.com/aip/g-43199b6ccac3d0a13b65c79a21c8cc2010aee226/oauth/callback` |

> [!NOTE]
> `MCP_AS_ISSUER` must be the canonical public URL used by clients. Discovery and token validation depend on this exact issuer value.

---

## Tools

### `search_jobs`

Runs synchronous job search for the authenticated user.

- Input: `searchConfigurations[]`
- Each configuration includes:
  - `provider` (string)
  - `model` (string)
  - `searchCompanies` (boolean)
  - `searchWithUserPrompts` (boolean)
- Validation: at least one of `searchCompanies` or `searchWithUserPrompts` must be `true`
- Output: `jobsFound[]` with `url` and `source`

### `get_user_info`

Returns a job-search-safe subset of the authenticated user's Jobshunter profile (email, notification preferences, location, job metadata, and more). Secrets and authorization-sensitive fields — verification token, phone number, security roles, and stored prompts — are never returned.

> [!TIP]
> Full request/response schema examples are in [`src/main/resources/openapi.yaml`](src/main/resources/openapi.yaml).

---

## Architecture

Two curated architecture walkthroughs are available:

- Auth + initialization flow: [`architecture/MCP_Server_Authentication_Initialization_Flow.md`](architecture/MCP_Server_Authentication_Initialization_Flow.md)
- Tool invocation flow: [`architecture/MCP_Tool_Invocation_Flow.md`](architecture/MCP_Tool_Invocation_Flow.md)

High-level sequence:

1. Client discovers OAuth metadata and JWKS from MCP.
2. Client authenticates through `/authorize` and `/token` (Google-backed broker flow).
3. MCP mints access token for `/mcp`.
4. Tool call reaches `search_jobs` or `get_user_info`.
5. MCP mints delegated Jobshunter token and calls internal Jobshunter API.

---

## Authentication model

This implementation is **internal-AS only**.

- MCP access token (for `/mcp`):
  - `aud=<MCP_AS_MCP_AUDIENCE>`
  - `token_use=mcp_access`
  - default TTL: `15m`
- Delegated Jobshunter token (used server-to-server by MCP):
  - `aud=<MCP_AS_JOBSHUNTER_AUDIENCE>`
  - `token_use=jobshunter_delegated`
  - default TTL: `5m`

Google `id_token` is used as identity proof during OAuth exchange; it is not reused as MCP access token.

---

## Endpoints

| Method | Path | Auth required | Purpose |
|---|---|---|---|
| `POST` | `/mcp` | Yes (MCP JWT) | MCP JSON-RPC endpoint (`initialize`, `tools/list`, `tools/call`) |
| `GET` | `/.well-known/oauth-protected-resource` | No | OAuth protected resource metadata |
| `GET` | `/.well-known/oauth-authorization-server` | No | OAuth authorization server metadata |
| `GET` | `/.well-known/jwks.json` | No | MCP JWKS for token verification |
| `GET` | `/authorize` | No | OAuth authorize bridge with strict validation |
| `POST` | `/token` | No | OAuth code exchange + MCP token issuance |

---

## Security policy

- Public endpoints are explicitly allowlisted:
  - `GET /.well-known/oauth-protected-resource`
  - `GET /.well-known/oauth-authorization-server`
  - `GET /.well-known/jwks.json`
  - `GET /authorize`
  - `POST /token`
- `/mcp` requires a valid MCP JWT; missing or invalid token returns `401`.
- Non-allowlisted endpoints are denied by default (`403`).

---

## Configuration reference

### Core required variables

| Variable | Required | Description |
|---|---|---|
| `GOOGLE_CLIENT_ID` | Yes | Google OAuth client id |
| `GOOGLE_CLIENT_SECRET` | Yes | Google OAuth client secret |
| `MCP_AS_ISSUER` | Yes | Canonical public issuer URL for discovery and JWT `iss` |
| `MCP_AS_MCP_AUDIENCE` | Yes | Audience accepted on `/mcp` |
| `MCP_AS_JOBSHUNTER_AUDIENCE` | Yes | Audience expected by Jobshunter for delegated token |
| `JOBSHUNTER_BASE_URL` | Yes | Base URL of Jobshunter internal API |
| `JOBSHUNTER_TRUST_STORE_PASSWORD` | Yes | Trust-store password when trust store is configured |

### Optional / defaulted variables

| Variable | Default | Description |
|---|---|---|
| `MCP_AS_SIGNING_KEY_PEM` | empty | PKCS#8 RSA private key; if empty, ephemeral key generated at startup |
| `MCP_AS_KEY_ID` | `mcp-key-1` | JWT `kid` exposed in JWKS |
| `MCP_OAUTH_ADDITIONAL_AUTHORIZE_PARAM_1` | empty | Optional extra allowlisted `/authorize` parameter |

### Fixed runtime defaults from config

| Setting | Value |
|---|---|
| MCP protocol | `STREAMABLE` |
| MCP server type | `SYNC` |
| MCP request timeout | `5m` |
| MCP access token TTL | `15m` |
| Delegated token TTL | `5m` |
| Jobshunter connect timeout | `5s` |
| Jobshunter response timeout | `25m` |
| Jobshunter trust store | `classpath:ngrok-truststore.p12` |

### OAuth hardening highlights

- Enforced redirect allowlist on `/authorize` and `/token`
- Strict unknown-parameter rejection for authorize/token requests
- Optional loopback redirects with restricted callback paths
- Discovery metadata built from configured issuer, not request headers

---

## Docker deployment

`docker-compose.yml` runs this service on host port `9002` -> container port `8081`.

```bash
docker network create jobshunter-net
docker compose up --build
```

> [!WARNING]
> In docker-compose, `MCP_AS_SIGNING_KEY_PEM` is required (fail-fast). For production-like stability, always use a persistent signing key to avoid token/JWKS rotation on restart.

---

## Testing

Run default test suite:

```bash
mvn test
```

Run an integration test class explicitly:

```bash
mvn test -Dtest=McpSecurityFilterChainIT
```

> [!NOTE]
> Maven Surefire defaults include `*Test` classes. `*IT` classes may need explicit execution unless build plugins are adjusted.

---

## Troubleshooting

### Common startup failures

- Missing required env vars (`@ConfigurationProperties` validation fails fast)
- `JOBSHUNTER_TRUST_STORE_PASSWORD` missing while trust store is configured
- Invalid PEM format for `MCP_AS_SIGNING_KEY_PEM`

### `401` on `/mcp`

- Access token issuer (`iss`) mismatch against `MCP_AS_ISSUER`
- Access token audience mismatch against `MCP_AS_MCP_AUDIENCE`
- Invalid or missing `token_use` claim (`mcp_access`)

### OAuth errors (`/authorize` or `/token`)

- `redirect_uri` not in allowlist
- Missing PKCE parameters (`code_challenge_method=S256`)
- Unknown request parameters rejected by strict validation

### Jobshunter call failures

- `JOBSHUNTER_BASE_URL` unreachable
- TLS trust-store issues (`ngrok-truststore.p12` / password mismatch)
- Jobshunter side not configured to trust MCP delegated tokens

---

## Jobshunter trust configuration

Configure Jobshunter to validate delegated JWTs issued by MCP:

- `DELEGATED_AUTH_ISSUER_URI=<MCP_AS_ISSUER>`
- `DELEGATED_AUTH_AUDIENCE=<MCP_AS_JOBSHUNTER_AUDIENCE>`
- `DELEGATED_AUTH_JWKS_URI=<MCP_AS_ISSUER>/.well-known/jwks.json`

---

## Related docs

- OpenAPI contract: [`src/main/resources/openapi.yaml`](src/main/resources/openapi.yaml)
- Auth flow doc: [`architecture/MCP_Server_Authentication_Initialization_Flow.md`](architecture/MCP_Server_Authentication_Initialization_Flow.md)
- Tool flow doc: [`architecture/MCP_Tool_Invocation_Flow.md`](architecture/MCP_Tool_Invocation_Flow.md)

---

## License

- Main license text: [`LICENSE`](LICENSE)
- Romanian summary: [`LICENSE.ro.md`](LICENSE.ro.md)
