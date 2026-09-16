package com.jobshunter.mcp.config;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;

class LevelIconConverterTest {

  private final LevelIconConverter converter = new LevelIconConverter();
  private static final String ESC = String.valueOf((char) 27);

  @ParameterizedTest(name = "{0}")
  @MethodSource("iconTestCases")
  void convertReturnsDistinctIconPerLevel(String levelName, String expectedIcon) {
    ILoggingEvent event = eventWithLevel(Level.toLevel(levelName));
    assertThat(converter.convert(event)).isEqualTo(expectedIcon);
  }

  private static Stream<org.junit.jupiter.params.provider.Arguments> iconTestCases() {
    return Stream.of(
        org.junit.jupiter.params.provider.Arguments.of("ERROR", ESC + "[31m✖" + ESC + "[0m"),
        org.junit.jupiter.params.provider.Arguments.of("WARN", ESC + "[33m⚠" + ESC + "[0m"),
        org.junit.jupiter.params.provider.Arguments.of("INFO", ESC + "[34mℹ" + ESC + "[0m"),
        org.junit.jupiter.params.provider.Arguments.of("DEBUG", "⚙"),
        org.junit.jupiter.params.provider.Arguments.of("TRACE", "»")
    );
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
  @org.junit.jupiter.params.provider.CsvSource({
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
