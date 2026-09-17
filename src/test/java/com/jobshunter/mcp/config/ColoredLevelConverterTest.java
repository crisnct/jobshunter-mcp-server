package com.jobshunter.mcp.config;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class ColoredLevelConverterTest {

  private ColoredLevelConverter converter;

  @BeforeEach
  void setUp() {
    converter = new ColoredLevelConverter();
  }

  @Test
  void testErrorLevelIsRed() {
    LoggingEvent event = new LoggingEvent();
    event.setLevel(Level.ERROR);
    String result = converter.convert(event);
    assertTrue(result.contains("[91m"), "ERROR should contain red ANSI code [91m");
    assertTrue(result.contains("ERROR"), "ERROR should contain the level name");
    assertTrue(result.contains("[0m"), "Result should contain ANSI reset code");
  }

  @Test
  void testWarnLevelIsYellow() {
    LoggingEvent event = new LoggingEvent();
    event.setLevel(Level.WARN);
    String result = converter.convert(event);
    assertTrue(result.contains("[93m"), "WARN should contain yellow ANSI code [93m");
    assertTrue(result.contains("WARN"), "WARN should contain the level name");
    assertTrue(result.contains("[0m"), "Result should contain ANSI reset code");
  }

  @Test
  void testInfoLevelIsBlue() {
    LoggingEvent event = new LoggingEvent();
    event.setLevel(Level.INFO);
    String result = converter.convert(event);
    assertTrue(result.contains("[94m"), "INFO should contain blue ANSI code [94m");
    assertTrue(result.contains("INFO"), "INFO should contain the level name");
    assertTrue(result.contains("[0m"), "Result should contain ANSI reset code");
  }

  @Test
  void testDebugLevelIsGray() {
    LoggingEvent event = new LoggingEvent();
    event.setLevel(Level.DEBUG);
    String result = converter.convert(event);
    assertTrue(result.contains("[90m"), "DEBUG should contain gray ANSI code [90m");
    assertTrue(result.contains("DEBUG"), "DEBUG should contain the level name");
    assertTrue(result.contains("[0m"), "Result should contain ANSI reset code");
  }

  @Test
  void testTraceLevelIsGray() {
    LoggingEvent event = new LoggingEvent();
    event.setLevel(Level.TRACE);
    String result = converter.convert(event);
    assertTrue(result.contains("[90m"), "TRACE should contain gray ANSI code [90m");
    assertTrue(result.contains("TRACE"), "TRACE should contain the level name");
    assertTrue(result.contains("[0m"), "Result should contain ANSI reset code");
  }
}
