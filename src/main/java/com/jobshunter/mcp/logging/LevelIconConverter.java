package com.jobshunter.mcp.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Logback conversion word ({@code %icon}) that renders a distinct Unicode glyph per log level,
 * making severity visually scannable in console/file output.
 */
public class LevelIconConverter extends ClassicConverter {

  @Override
  public String convert(ILoggingEvent event) {
    Level level = event.getLevel();
    return switch (level.toInt()) {
      case Level.ERROR_INT -> "⛔"; // ⛔ no entry
      case Level.WARN_INT -> "⚠"; // ⚠ warning sign
      case Level.INFO_INT -> "ℹ"; // ℹ information source
      case Level.DEBUG_INT -> "🔧"; // 🔧 wrench
      case Level.TRACE_INT -> "🔍"; // 🔍 magnifying glass
      default -> "•"; // • bullet, fallback for custom levels
    };
  }
}
