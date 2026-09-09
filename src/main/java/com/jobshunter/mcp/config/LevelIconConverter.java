package com.jobshunter.mcp.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Logback pattern converter that renders a distinct Unicode glyph per log severity level,
 * registered as the {@code %levelIcon} conversion word in logback-spring.xml.
 */
public class LevelIconConverter extends ClassicConverter {

  @Override
  public String convert(ILoggingEvent event) {
    Level level = event.getLevel();
    return switch (level.toInt()) {
      case Level.ERROR_INT -> "✖";
      case Level.WARN_INT -> "⚠";
      case Level.INFO_INT -> "ℹ";
      case Level.DEBUG_INT -> "⚙";
      case Level.TRACE_INT -> "»";
      default -> "•";
    };
  }
}
