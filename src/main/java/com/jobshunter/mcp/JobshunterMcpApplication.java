package com.jobshunter.mcp;

import com.jobshunter.mcp.config.JobshunterProperties;
import com.jobshunter.mcp.config.TokenExchangeProperties;
import com.jobshunter.mcp.security.McpSecurityProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({
    JobshunterProperties.class,
    McpSecurityProperties.class,
    TokenExchangeProperties.class
})
public class JobshunterMcpApplication {

  static void main(String[] args) {
    SpringApplication.run(JobshunterMcpApplication.class, args);
  }
}
