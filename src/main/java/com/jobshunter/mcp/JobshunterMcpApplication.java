package com.jobshunter.mcp;

import com.jobshunter.mcp.config.JobshunterProperties;
import com.jobshunter.mcp.security.McpAuthorizationServerProperties;
import com.jobshunter.mcp.security.McpOAuthProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({
    JobshunterProperties.class,
    McpOAuthProperties.class,
    McpAuthorizationServerProperties.class
})
public class JobshunterMcpApplication {

  static void main(String[] args) {
    SpringApplication.run(JobshunterMcpApplication.class, args);
  }
}
