# MCP Server Authentication and Initialization Flow

```mermaid
%%{init: {'theme':'base','themeVariables':{
'fontSize':'16px',
'fontFamily':'Inter, Segoe UI, Arial',
'sequenceNumberColor':'#111827',
'lineColor':'#6b7280'
},'themeCSS':'
.actor:nth-of-type(1) rect{fill:#dbeafe!important;stroke:#2563eb!important;stroke-width:2px!important;}
.actor-line:nth-of-type(1){stroke:#2563eb!important;stroke-width:2px!important;}
.actor:nth-of-type(2) rect{fill:#dcfce7!important;stroke:#16a34a!important;stroke-width:2px!important;}
.actor-line:nth-of-type(2){stroke:#16a34a!important;stroke-width:2px!important;}
.actor:nth-of-type(3) rect{fill:#ffedd5!important;stroke:#ea580c!important;stroke-width:2px!important;}
.actor-line:nth-of-type(3){stroke:#ea580c!important;stroke-width:2px!important;}
.actor:nth-of-type(4) rect{fill:#ede9fe!important;stroke:#7c3aed!important;stroke-width:2px!important;}
.actor-line:nth-of-type(4){stroke:#7c3aed!important;stroke-width:2px!important;}
.messageText{font-size:14px!important;}
'}}%%
sequenceDiagram
    autonumber
    participant McpClient as MCP Client
    participant McpServer as MCP Server
    participant Google as Google OAuth
    participant JobApi as Jobshunter API

    McpClient->>McpServer: GET /.well-known/oauth-protected-resource
    McpServer-->>McpClient: resource metadata and scopes
    McpClient->>McpServer: GET /.well-known/oauth-authorization-server
    McpServer-->>McpClient: issuer, endpoints, jwks_uri
    McpClient->>McpServer: GET /.well-known/jwks.json
    McpServer-->>McpClient: MCP public JWK set

    McpClient->>McpServer: GET /authorize with PKCE and scope
    McpServer-->>McpClient: 302 redirect to Google
    McpClient->>Google: open consent and authenticate user
    Google-->>McpClient: redirect with authorization code

    McpClient->>McpServer: POST /token with code and code_verifier
    McpServer->>Google: exchange code at token endpoint
    Google-->>McpServer: id_token, refresh_token optional
    McpServer->>McpServer: validate Google id_token
    McpServer->>McpServer: mint MCP access JWT (token_use mcp_access)
    McpServer-->>McpClient: access_token, expires_in, id_token

    McpClient->>McpServer: POST /mcp initialize with Bearer token
    McpServer->>McpServer: validate MCP JWT (signature, iss, aud, exp)
    alt JWT invalid
        McpServer-->>McpClient: 401 unauthorized
    else JWT valid
        McpServer-->>McpClient: initialize result and Mcp-Session-Id
    end

    McpClient->>McpServer: POST /mcp tools/list
    McpServer-->>McpClient: tools metadata
    McpClient->>McpServer: POST /mcp tools/call
    McpServer->>McpServer: mint delegated JWT (config token_use)
    McpServer->>JobApi: call Jobshunter with delegated Bearer
    JobApi-->>McpServer: business response
    McpServer-->>McpClient: tool result payload
```

The current implementation is internal-AS only: Google is used for identity proof, MCP mints its own access token for `/mcp`, and MCP mints a separate delegated token for Jobshunter calls.

Alignment with current code:
- `/mcp` authorization is `authenticated()` with issuer and audience checks from `mcp.authorization-server.*`.
- OAuth discovery metadata (`issuer`, `authorization_endpoint`, `token_endpoint`, `jwks_uri`, `resource`) is derived from the fixed `mcp.authorization-server.issuer` value.
- OAuth discovery metadata is not derived from `Host` or `X-Forwarded-*` request headers.
- No `required-scope` gate is applied on `/mcp`.
- Delegated token `token_use` is configurable via `mcp.authorization-server.jobshunter-delegated-token-use`.
