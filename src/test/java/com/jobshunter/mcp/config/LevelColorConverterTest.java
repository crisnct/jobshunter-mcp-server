package com.jobshunter.mcp.config;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;
import org.springframework.boot.ansi.AnsiOutput;

class LevelColorConverterTest {

  private LevelColorConverter converter;

  @BeforeEach
  void setUp() {
    AnsiOutput.setEnabled(AnsiOutput.Enabled.ALWAYS);
    converter = new LevelColorConverter();
    converter.start();
  }

  @AfterEach
  void tearDown() {
    AnsiOutput.setEnabled(AnsiOutput.Enabled.DETECT);
  }

  @ParameterizedTest
  @CsvSource({
      "ERROR, 31m",
      "WARN, 33m",
      "INFO, 34m",
      "DEBUG, 32m",
      "TRACE, 32m"
  })
  void transformColorsSeverityLevelsAppropriately(String levelName, String expectedAnsiSuffix) {
    ILoggingEvent event = eventWithLevel(Level.toLevel(levelName));

    String result = converter.transform(event, levelName);

    assertThat(result)
        .startsWith("\u001B[" + expectedAnsiSuffix)
        .endsWith("\u001B[0;39m")
        .contains(levelName);
  }

  @Test
  void transformUsesExplicitOptionWhenProvided() {
    converter.setOptionList(List.of("cyan"));
    ILoggingEvent event = eventWithLevel(Level.INFO);

    String result = converter.transform(event, "test-logger");

    // AnsiColor.CYAN code is 36m
    assertThat(result)
        .startsWith("\u001B[36m")
        .endsWith("\u001B[0;39m")
        .contains("test-logger");
  }

  @Test
  void transformHandlesNullEventGracefully() {
    String result = converter.transform(null, "fallback");

    assertThat(result).isEqualTo("fallback");
  }

  @Test
  void transformHandlesNullLevelGracefully() {
    ILoggingEvent event = Mockito.mock(ILoggingEvent.class);
    Mockito.when(event.getLevel()).thenReturn(null);

    String result = converter.transform(event, "fallback");

    assertThat(result).isEqualTo("fallback");
  }

  @ParameterizedTest
  @CsvSource({
      "ERROR, ✖, 31m",
      "WARN, ⚠, 33m",
      "INFO, ℹ, 34m"
  })
  void patternLayoutFormatsSeverityAndIconWithExpectedColors(
      String levelName, String expectedIcon, String expectedColorSuffix) {
    LoggerContext context = new LoggerContext();
    PatternLayout layout = new PatternLayout();
    layout.setContext(context);
    layout.getInstanceConverterMap().put("clr", LevelColorConverter::new);
    layout.getInstanceConverterMap().put("levelIcon", LevelIconConverter::new);
    layout.setPattern("%clr(%levelIcon) %clr(%-5p)");
    layout.start();

    LoggingEvent event = new LoggingEvent(
        "com.jobshunter.TestLogger",
        context.getLogger("com.jobshunter.TestLogger"),
        Level.toLevel(levelName),
        "test message",
        null,
        null
    );

    String formatted = layout.doLayout(event);

    assertThat(formatted)
        .contains("\u001B[" + expectedColorSuffix + expectedIcon + "\u001B[0;39m")
        .contains("\u001B[" + expectedColorSuffix + levelName);
  }

  private static ILoggingEvent eventWithLevel(Level level) {
    ILoggingEvent event = Mockito.mock(ILoggingEvent.class);
    Mockito.when(event.getLevel()).thenReturn(level);
    return event;
  }
}
