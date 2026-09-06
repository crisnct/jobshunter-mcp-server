# MCP Tool Invocation Flow

```mermaid
%%{init: {'theme':'base','themeVariables':{
'fontSize':'16px',
'fontFamily':'Inter, Segoe UI, Arial',
'sequenceNumberColor':'#111827',
'lineColor':'#6b7280'
},'themeCSS':'
.actor:nth-of-type(1) rect{fill:#fee2e2!important;stroke:#dc2626!important;stroke-width:2px!important;}
.actor-line:nth-of-type(1){stroke:#dc2626!important;stroke-width:2px!important;}
.actor:nth-of-type(2) rect{fill:#dbeafe!important;stroke:#2563eb!important;stroke-width:2px!important;}
.actor-line:nth-of-type(2){stroke:#2563eb!important;stroke-width:2px!important;}
.actor:nth-of-type(3) rect{fill:#dcfce7!important;stroke:#16a34a!important;stroke-width:2px!important;}
.actor-line:nth-of-type(3){stroke:#16a34a!important;stroke-width:2px!important;}
.actor:nth-of-type(4) rect{fill:#fef3c7!important;stroke:#d97706!important;stroke-width:2px!important;}
.actor-line:nth-of-type(4){stroke:#d97706!important;stroke-width:2px!important;}
.actor:nth-of-type(5) rect{fill:#e0e7ff!important;stroke:#4f46e5!important;stroke-width:2px!important;}
.actor-line:nth-of-type(5){stroke:#4f46e5!important;stroke-width:2px!important;}
.actor:nth-of-type(6) rect{fill:#ede9fe!important;stroke:#7c3aed!important;stroke-width:2px!important;}
.actor-line:nth-of-type(6){stroke:#7c3aed!important;stroke-width:2px!important;}
.messageText{font-size:14px!important;}
'}}%%
sequenceDiagram
    autonumber
    participant AIHost as AI Host
    participant McpClient as MCP Client
    participant McpServer as MCP Server
    participant ToolSvc as JobSearchTool Service
    participant JhClient as JobshunterClient Adapter
    participant JobApi as Jobshunter API

    AIHost->>McpClient: decide tool and send tools/call
    McpClient->>McpServer: POST /mcp tools/call with Bearer and session id
    McpServer->>McpServer: validate JWT signature, iss, aud, exp, token_use
    alt JWT invalid
        McpServer-->>McpClient: 401 unauthorized
        McpClient-->>AIHost: re-authentication required
    else JWT valid
        McpServer->>ToolSvc: dispatch @Tool callback
        ToolSvc->>ToolSvc: validate input and business rules
        alt input invalid
            ToolSvc-->>McpServer: JobshunterApiException (validation)
            McpServer-->>McpClient: tool error result
            McpClient-->>AIHost: structured error
        else input valid
            ToolSvc->>ToolSvc: read JwtAuthenticationToken
            ToolSvc->>ToolSvc: mint delegated JWT (config token_use)
            ToolSvc->>JhClient: searchJobs or getUserInfo
            JhClient->>JobApi: call internal API with delegated Bearer (connect/response timeouts)
            alt Jobshunter error, timeout, or unreachable
                JobApi-->>JhClient: 4xx/5xx or connection failure
                JhClient-->>ToolSvc: mapped JobshunterApiException
                ToolSvc-->>McpServer: propagate exception
                McpServer-->>McpClient: tool error result
                McpClient-->>AIHost: structured error
            else success
                JobApi-->>JhClient: business response
                JhClient-->>ToolSvc: mapped DTO
                ToolSvc-->>McpServer: tool result payload
                McpServer-->>McpClient: JSON-RPC response
                McpClient-->>AIHost: structured result
            end
        end
    end
```

This sequence reflects current code paths for `search_jobs` and `get_user_info`:

- MCP JWT validation checks signature, issuer, audience, and `token_use` (`McpSecurityConfig`) before any tool dispatches.
- `JobSearchTool` validates business rules up front — e.g. `search_jobs` requires at least one of `searchCompanies` or `searchWithUserPrompts` — and fails fast with a `JobshunterApiException` before any delegated call is made.
- Delegated token minting reads the caller's `JwtAuthenticationToken` from the security context and mints a short-lived Jobshunter-audience JWT via `DelegatedTokenResolver` / `McpTokenIssuer`.
- `JobshunterClient` calls the internal Jobshunter API through a `RestClient` configured with explicit connect/response timeouts and optional mutual TLS trust-store configuration (`RestClientConfig`, `JobshunterProperties.Ssl`).
- Adapter failures are normalized into `JobshunterApiException`: HTTP status codes are mapped per-range (400/401/403/404/5xx), timeouts are detected and reported distinctly, and a likely HTTP/HTTPS protocol mismatch against the configured base URL is called out explicitly to speed up misconfiguration diagnosis.
