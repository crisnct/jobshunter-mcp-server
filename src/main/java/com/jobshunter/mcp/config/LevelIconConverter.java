package com.jobshunter.mcp.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Logback pattern converter that renders a distinct Unicode glyph per log severity level with colors,
 * registered as the {@code %levelIcon} conversion word in logback-spring.xml.
 */
public class LevelIconConverter extends ClassicConverter {
  private static final String ANSI_RED = "\u001B[31m";
  private static final String ANSI_YELLOW = "\u001B[33m";
  private static final String ANSI_BLUE = "\u001B[34m";
  private static final String ANSI_RESET = "\u001B[0m";

  @Override
  public String convert(ILoggingEvent event) {
    Level level = event.getLevel();
    return switch (level.toInt()) {
      case Level.ERROR_INT -> ANSI_RED + "✖" + ANSI_RESET;
      case Level.WARN_INT -> ANSI_YELLOW + "⚠" + ANSI_RESET;
      case Level.INFO_INT -> ANSI_BLUE + "ℹ" + ANSI_RESET;
      case Level.DEBUG_INT -> "⚙";
      case Level.TRACE_INT -> "»";
      default -> "•";
    };
  }
}
