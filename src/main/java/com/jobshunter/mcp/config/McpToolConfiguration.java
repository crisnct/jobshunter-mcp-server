package com.jobshunter.mcp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobshunter.mcp.tool.JobSearchTool;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpToolConfiguration {

  @Bean
  ToolCallbackProvider jobshunterTools(JobSearchTool jobSearchTool) {
    return MethodToolCallbackProvider.builder()
        .toolObjects(jobSearchTool)
        .build();
  }

  /**
   * This application does not otherwise expose a top-level {@link ObjectMapper} bean (the
   * webmvc/Jackson message-converter auto-configuration keeps its own internal instance without
   * publishing one), so {@link JobSearchTool} needs its own explicit, predictable one rather than
   * relying on autoconfiguration that may or may not run.
   */
  @Bean
  ObjectMapper objectMapper() {
    return new ObjectMapper();
  }
}
