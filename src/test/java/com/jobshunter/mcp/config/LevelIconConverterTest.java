package com.jobshunter.mcp.config;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;

class LevelIconConverterTest {

  private final LevelIconConverter converter = new LevelIconConverter();
  private static final String ANSI_RESET = "[0m";
  private static final String ANSI_RED = "[31m";
  private static final String ANSI_YELLOW = "[33m";
  private static final String ANSI_BLUE = "[34m";

  @ParameterizedTest
  @CsvSource({
      "ERROR, ✖",
      "WARN, ⚠",
      "INFO, ℹ",
      "DEBUG, ⚙",
      "TRACE, »"
  })
  void convertReturnsDistinctIconPerLevel(String levelName, String expectedIcon) {
    ILoggingEvent event = eventWithLevel(Level.toLevel(levelName));
    String result = converter.convert(event);

    if (levelName.equals("ERROR")) {
      assertThat(result).isEqualTo(ANSI_RED + expectedIcon + ANSI_RESET);
    } else if (levelName.equals("WARN")) {
      assertThat(result).isEqualTo(ANSI_YELLOW + expectedIcon + ANSI_RESET);
    } else if (levelName.equals("INFO")) {
      assertThat(result).isEqualTo(ANSI_BLUE + expectedIcon + ANSI_RESET);
    } else {
      assertThat(result).isEqualTo(expectedIcon);
    }
  }

  @Test
  void convertReturnsDistinctIconsForEveryLevel() {
    long distinctIcons = Stream.of(Level.ERROR, Level.WARN, Level.INFO, Level.DEBUG, Level.TRACE)
        .map(level -> converter.convert(eventWithLevel(level)))
        .distinct()
        .count();

    assertThat(distinctIcons).isEqualTo(5);
  }

  @ParameterizedTest
  @CsvSource({
      "OFF, •",
      "ALL, •"
  })
  void convertReturnsDefaultIconForUnmappedLevel(String levelName, String expectedIcon) {
    ILoggingEvent event = eventWithLevel(Level.toLevel(levelName));

    assertThat(converter.convert(event)).isEqualTo(expectedIcon);
  }

  @Test
  void errorIconIsRed() {
    ILoggingEvent event = eventWithLevel(Level.ERROR);
    assertThat(converter.convert(event)).contains(ANSI_RED).contains("✖").endsWith(ANSI_RESET);
  }

  @Test
  void warningIconIsYellow() {
    ILoggingEvent event = eventWithLevel(Level.WARN);
    assertThat(converter.convert(event)).contains(ANSI_YELLOW).contains("⚠").endsWith(ANSI_RESET);
  }

  @Test
  void infoIconIsBlue() {
    ILoggingEvent event = eventWithLevel(Level.INFO);
    assertThat(converter.convert(event)).contains(ANSI_BLUE).contains("ℹ").endsWith(ANSI_RESET);
  }

  private static ILoggingEvent eventWithLevel(Level level) {
    ILoggingEvent event = Mockito.mock(ILoggingEvent.class);
    Mockito.when(event.getLevel()).thenReturn(level);
    return event;
  }
}
