/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cn.lgs.semevosql.external.mcp;

import cn.lgs.semevosql.external.mcp.ExternalSemanticQueryFacade.ExternalQueryPlan;
import cn.lgs.semevosql.external.mcp.ExternalSemanticQueryFacade.PlanValidationResult;
import cn.lgs.semevosql.external.mcp.ExternalSemanticQueryFacade.QueryExecutionResult;
import cn.lgs.semevosql.external.mcp.ExternalSemanticQueryFacade.SemanticAssetRef;
import cn.lgs.semevosql.external.mcp.ExternalSemanticQueryFacade.SemanticContextResult;
import cn.lgs.semevosql.external.mcp.ExternalSemanticQueryFacade.SemanticSearchResult;
import cn.lgs.semevosql.external.mcp.ProjectMcpQueryFacade.McpQueryResult;
import cn.lgs.semevosql.external.mcp.ProjectMcpQueryFacade.QueryCommand;
import java.util.List;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/** Stable project-scoped MCP surface. Transport state never becomes business-session state. */
@Component
public class ExternalProjectMcpTools {

    private static final String AUTHORIZATION = "semevosql.authorization";

    private final McpIntegrationAuthenticator authenticator;
    private final ExternalSemanticQueryFacade semanticFacade;
    private final ProjectMcpQueryFacade queryFacade;

    public ExternalProjectMcpTools(McpIntegrationAuthenticator authenticator, ExternalSemanticQueryFacade semanticFacade,
            ProjectMcpQueryFacade queryFacade) {
        this.authenticator = authenticator;
        this.semanticFacade = semanticFacade;
        this.queryFacade = queryFacade;
    }

    @Tool(name = "query",
            description = "Submit or continue a governed natural-language query. New calls create an Episode pinned to the project's current Active Semantic Version; episodeId continues the same Episode; parentEpisodeId creates a child Episode.")
    public McpQueryResult query(@ToolParam(description = "Natural-language business query or clarification/correction input") String input,
            @ToolParam(description = "Existing Episode to continue", required = false) String episodeId,
            @ToolParam(description = "Completed parent Episode for a new child analysis", required = false) String parentEpisodeId,
            @ToolParam(description = "Stable idempotency key for retries", required = false) String requestId,
            ToolContext toolContext) {
        return queryFacade.query(deployment(toolContext), new QueryCommand(input, episodeId, parentEpisodeId, requestId));
    }

    @Tool(name = "query_status",
            description = "Read durable status, clarification, SQL, evidence and structured result for an Episode without re-planning.")
    public McpQueryResult queryStatus(@ToolParam(description = "Durable Episode id") String episodeId,
            ToolContext toolContext) {
        return queryFacade.status(deployment(toolContext), episodeId);
    }

    public SemanticSearchResult searchSemantics(@ToolParam(description = "Business terms or semantic concepts") String query,
            @ToolParam(description = "Maximum hits, default 20 and capped at 50", required = false) Integer limit,
            ToolContext toolContext) {
        return semanticFacade.search(deployment(toolContext), query, limit);
    }

    public SemanticContextResult getSemanticContext(
            @ToolParam(description = "Semantic asset type/key pairs returned by search_semantics") List<SemanticAssetRef> assets,
            ToolContext toolContext) {
        return semanticFacade.context(deployment(toolContext), assets);
    }

    public PlanValidationResult validateQueryPlan(@ToolParam(description = "Caller-authored semantic blueprint") ExternalQueryPlan plan,
            ToolContext toolContext) {
        return semanticFacade.validate(deployment(toolContext), plan);
    }

    public QueryExecutionResult executeQueryPlan(
            @ToolParam(description = "Caller-authored governed semantic blueprint") ExternalQueryPlan plan,
            ToolContext toolContext) {
        return semanticFacade.execute(deployment(toolContext), plan);
    }

    public Object getQueryResult(@ToolParam(description = "Durable query id") String queryId, ToolContext toolContext) {
        return semanticFacade.getResult(deployment(toolContext), queryId);
    }

    private ProjectMcpDeployment deployment(ToolContext toolContext) {
        var exchange = McpToolUtils.getMcpExchange(toolContext)
            .orElseThrow(() -> new SecurityException("MCP exchange context is required"));
        Object authorization = exchange.transportContext().get(AUTHORIZATION);
        if (!(authorization instanceof String value)) {
            throw new SecurityException("MCP bearer credential is required");
        }
        return authenticator.authenticateAuthorization(value).deployment();
    }

    public static String authorizationContextKey() {
        return AUTHORIZATION;
    }
}
