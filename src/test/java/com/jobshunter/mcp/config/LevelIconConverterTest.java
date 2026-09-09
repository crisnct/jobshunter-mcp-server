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

    assertThat(converter.convert(event)).isEqualTo(expectedIcon);
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

  private static ILoggingEvent eventWithLevel(Level level) {
    ILoggingEvent event = Mockito.mock(ILoggingEvent.class);
    Mockito.when(event.getLevel()).thenReturn(level);
    return event;
  }
}
