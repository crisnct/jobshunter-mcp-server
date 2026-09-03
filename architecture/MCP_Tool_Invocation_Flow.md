# MCP Tool Invocation Flow

```mermaid
sequenceDiagram
    autonumber
    participant User as User
    participant AIHost as AIClient_or_ModelHost
    participant McpClient as MCPClient
    participant McpServer as MCPServer_jobshunter_mcp_server
    participant ToolSvc as JobSearchTool_Service
    participant JhClient as JobshunterClient_HTTPAdapter
    participant JobApi as JobshunterAPI

    Note over AIHost,McpClient: SecurityBoundary_AI_and_MCPClient (reasoning + tool selection + token/session ownership)
    Note over McpServer: SecurityBoundary_MCPServer (JWT authz/authn gate on /mcp)
    Note over JobApi: SecurityBoundary_Jobshunter (independent authz and business logic)

    User->>AIHost: Ask for job search / profile data
    activate AIHost
    AIHost->>AIHost: Decide tool use from intent and available tool schemas
    AIHost->>McpClient: Request tool invocation (name + arguments)
    deactivate AIHost

    activate McpClient
    opt Tools not yet known in active MCP session
        McpClient->>McpServer: POST /mcp method=tools-list<br/>Headers: Authorization Bearer token, Mcp-Session-Id session_id
        activate McpServer
        McpServer-->>McpClient: 200 tool catalog + input schemas
        deactivate McpServer
    end

    McpClient->>McpServer: POST /mcp method=tools-call<br/>params.name search_jobs or get_user_info<br/>params.arguments structured JSON
    Note right of McpClient: HTTP transport Streamable MCP endpoint<br/>Authorization Bearer token
    activate McpServer
    McpServer->>McpServer: Security filter validates JWT issuer/audience/exp/(optional scope)

    alt MCP authentication or authorization fails
        McpServer-->>McpClient: HTTP 401 Unauthorized
        McpClient-->>AIHost: Tool call rejected (reauth required)
    else MCP authentication succeeds
        McpServer->>ToolSvc: Dispatch @Tool callback by params.name
        activate ToolSvc

        alt Tool is search_jobs
            ToolSvc->>ToolSvc: Validate input structure (@NotEmpty, @Valid)
            ToolSvc->>ToolSvc: Apply business rule (at least one search flag true)
            alt Invalid arguments
                ToolSvc-->>McpServer: Raise JobshunterApiException (invalid search configuration)
            else Arguments valid
                ToolSvc->>ToolSvc: Read JwtAuthenticationToken from SecurityContext
                ToolSvc->>ToolSvc: Mint delegated JWT via DelegatedTokenResolver
                ToolSvc->>JhClient: searchJobs(searchConfigurations, delegatedToken)
                activate JhClient
                JhClient->>JhClient: validateUserToken(non-empty)
                JhClient->>JobApi: POST /api/internal/search_jobs<br/>Headers: Authorization Bearer token, Content-Type application-json<br/>Body searchConfigurations array
                activate JobApi
                JobApi->>JobApi: Authenticate delegated token + execute job aggregation logic
                alt Upstream error (4xx/5xx/timeout/connectivity)
                    JobApi-->>JhClient: HTTP error or transport failure
                    JhClient-->>ToolSvc: Map to JobshunterApiException (domain message)
                else Upstream success
                    JobApi-->>JhClient: 200 SearchJobsResponse JSON
                    JhClient-->>ToolSvc: SearchJobsResponse DTO
                end
                deactivate JobApi
                deactivate JhClient
            end

        else Tool is get_user_info
            ToolSvc->>ToolSvc: Read JwtAuthenticationToken from SecurityContext
            ToolSvc->>ToolSvc: Mint delegated JWT via DelegatedTokenResolver
            ToolSvc->>JhClient: getUserInfo(delegatedToken)
            activate JhClient
            JhClient->>JobApi: GET /api/internal/me<br/>Header Authorization Bearer token
            activate JobApi
            JobApi->>JobApi: Authenticate delegated token + load profile data
            JobApi-->>JhClient: 200 UserInfoResponse JSON
            deactivate JobApi
            JhClient-->>ToolSvc: UserInfoResponse DTO
            deactivate JhClient
        end

        ToolSvc-->>McpServer: Return DTO result or throw domain exception
        deactivate ToolSvc
        McpServer-->>McpClient: JSON-RPC tools-call result (or MCP error payload)

        McpClient-->>AIHost: Structured tool result
        activate AIHost
        AIHost->>AIHost: Ground response on tool payload
        AIHost-->>User: Final answer
        deactivate AIHost
    end
    deactivate McpServer
    deactivate McpClient
```

This flow shows the full runtime chain from user intent to AI tool reasoning, MCP transport, server-side validation, delegated Jobshunter API calls, and final answer synthesis.

Important security and architecture considerations:
- Authorization is enforced twice by design: once at MCP ingress, then again by Jobshunter when delegated token is received.
- Tool argument validation combines schema-level constraints and business-level rules before upstream calls.
- Identity propagation is explicit via dedicated delegated JWT (`aud=jobshunter`, `token_use=jobshunter_delegated`).
- Error domains are separated: MCP auth failures return `401`, while upstream/service failures are mapped into tool-level exceptions.

Assumptions and unknowns (explicit):
- Exact JSON-RPC/MCP error envelope for uncaught runtime exceptions depends on Spring AI internals.
- AI host decision strategy for choosing tools is implementation-dependent and not defined by this repository.
- Jobshunter internal authorization rules for delegated tokens are external to this codebase.
