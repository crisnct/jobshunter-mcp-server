package com.jobshunter.mcp.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.ansi.AnsiColor;
import org.springframework.boot.ansi.AnsiElement;
import org.springframework.boot.logging.logback.ColorConverter;

/**
 * Logback composite converter that customizes severity-based ANSI coloring:
 * ERROR as RED, WARN as YELLOW, and INFO as BLUE.
 * Registered as the {@code %clr} conversion word in logback-spring.xml.
 */
public class LevelColorConverter extends ColorConverter {

  private static final Map<Integer, AnsiElement> LEVELS;

  static {
    Map<Integer, AnsiElement> levels = new HashMap<>();
    levels.put(Level.ERROR_INTEGER, AnsiColor.RED);
    levels.put(Level.WARN_INTEGER, AnsiColor.YELLOW);
    levels.put(Level.INFO_INTEGER, AnsiColor.BLUE);
    LEVELS = Collections.unmodifiableMap(levels);
  }

  @Override
  protected String transform(ILoggingEvent event, String in) {
    List<String> options = getOptionList();
    if (options != null && !options.isEmpty()) {
      return super.transform(event, in);
    }
    if (event == null || event.getLevel() == null) {
      return in;
    }
    AnsiElement element = LEVELS.get(event.getLevel().toInteger());
    if (element == null) {
      return super.transform(event, in);
    }
    return toAnsiString(in, element);
  }
}
