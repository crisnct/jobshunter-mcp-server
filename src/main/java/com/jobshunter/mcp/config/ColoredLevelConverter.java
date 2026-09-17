package com.jobshunter.mcp.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Logback pattern converter that renders the log level with ANSI color codes based on severity,
 * registered as the {@code %coloredLevel} conversion word in logback-spring.xml.
 * Error: RED, Warning: YELLOW, Info: BLUE
 */
public class ColoredLevelConverter extends ClassicConverter {

  private static final String RESET = "[0m";
  private static final String RED = "[91m";
  private static final String YELLOW = "[93m";
  private static final String BLUE = "[94m";
  private static final String GRAY = "[90m";

  @Override
  public String convert(ILoggingEvent event) {
    Level level = event.getLevel();
    String levelStr = String.format("%5s", level.toString());
    String color = getColorForLevel(level);
    return color + levelStr + RESET;
  }

  private String getColorForLevel(Level level) {
    return switch (level.toInt()) {
      case Level.ERROR_INT -> RED;
      case Level.WARN_INT -> YELLOW;
      case Level.INFO_INT -> BLUE;
      case Level.DEBUG_INT, Level.TRACE_INT -> GRAY;
      default -> RESET;
    };
  }
}
