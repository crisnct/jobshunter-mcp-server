package com.jobshunter.mcp.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Logback pattern converter that renders a distinct Unicode glyph per log severity level,
 * registered as the {@code %levelIcon} conversion word in logback-spring.xml.
 * Color codes: RED for ERROR, YELLOW for WARN, BLUE for INFO, and default for others.
 */
public class LevelIconConverter extends ClassicConverter {

  private static final String ANSI_RESET = "\033[0m";
  private static final String ANSI_RED = "\033[31m";
  private static final String ANSI_YELLOW = "\033[33m";
  private static final String ANSI_BLUE = "\033[34m";

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
