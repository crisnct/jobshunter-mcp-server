package com.jobshunter.mcp.config;

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
}
