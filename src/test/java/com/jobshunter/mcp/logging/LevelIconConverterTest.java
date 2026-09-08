package com.jobshunter.mcp.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LevelIconConverterTest {
  private final LevelIconConverter converter = new LevelIconConverter();
  private final LoggerContext loggerContext = new LoggerContext();

  @Test
  void shouldRenderDistinctIconForEachStandardSeverityLevel() {
    List<Level> levels = List.of(Level.ERROR, Level.WARN, Level.INFO, Level.DEBUG, Level.TRACE);
    Set<String> icons = new HashSet<>();

    for (Level level : levels) {
      String icon = converter.convert(eventAt(level));
      assertNotNull(icon);
      icons.add(icon);
    }

    assertEquals(levels.size(), icons.size(), "each severity level must render a distinct icon");
  }

  @Test
  void shouldFallBackToBulletForUnmappedLevel() {
    assertEquals("•", converter.convert(eventAt(Level.ALL)));
  }

  private LoggingEvent eventAt(Level level) {
    LoggingEvent event = new LoggingEvent();
    event.setLoggerContext(loggerContext);
    event.setLevel(level);
    event.setMessage("test message");
    return event;
  }
}
