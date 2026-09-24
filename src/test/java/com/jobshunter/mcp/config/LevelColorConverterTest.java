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
import org.springframework.boot.ansi.AnsiColor;
import org.springframework.boot.ansi.AnsiOutput;

class LevelColorConverterTest {

  private final LevelColorConverter converter = new LevelColorConverter();

  @BeforeEach
  void setUp() {
    AnsiOutput.setEnabled(AnsiOutput.Enabled.ALWAYS);
    converter.start();
  }

  @AfterEach
  void tearDown() {
    AnsiOutput.setEnabled(AnsiOutput.Enabled.DETECT);
  }

  @ParameterizedTest
  @CsvSource({
      "ERROR, 31", // AnsiColor.RED code
      "WARN, 33",  // AnsiColor.YELLOW code
      "INFO, 34"   // AnsiColor.BLUE code
  })
  void transformColorsLevelCorrectly(String levelName, String ansiCode) {
    ILoggingEvent event = eventWithLevel(Level.toLevel(levelName));

    String result = converter.transform(event, levelName);

    assertThat(result)
        .startsWith("\033[" + ansiCode + "m")
        .contains(levelName);
  }

  @Test
  void transformColorsInfoSpecificallyAsBlue() {
    ILoggingEvent event = eventWithLevel(Level.INFO);

    String result = converter.transform(event, "INFO");

    assertThat(result).isEqualTo(AnsiOutput.toString(AnsiColor.BLUE, "INFO"));
  }

  @Test
  void transformColorsErrorSpecificallyAsRed() {
    ILoggingEvent event = eventWithLevel(Level.ERROR);

    String result = converter.transform(event, "ERROR");

    assertThat(result).isEqualTo(AnsiOutput.toString(AnsiColor.RED, "ERROR"));
  }

  @Test
  void transformColorsWarnSpecificallyAsYellow() {
    ILoggingEvent event = eventWithLevel(Level.WARN);

    String result = converter.transform(event, "WARN");

    assertThat(result).isEqualTo(AnsiOutput.toString(AnsiColor.YELLOW, "WARN"));
  }

  @Test
  void transformWithExplicitOptionRespectsOption() {
    converter.setOptionList(List.of("faint"));
    ILoggingEvent event = eventWithLevel(Level.INFO);

    String result = converter.transform(event, "test");

    assertThat(result)
        .startsWith("\033[2m")
        .contains("test");
  }

  @Test
  void transformWithUnmappedLevelFallsBackToDefault() {
    ILoggingEvent event = eventWithLevel(Level.DEBUG);

    String result = converter.transform(event, "DEBUG");

    assertThat(result)
        .startsWith("\033[32m")
        .contains("DEBUG");
  }

  @Test
  void transformWithNullEventHandlesGracefully() {
    String result = converter.transform(null, "TEXT");

    assertThat(result).isEqualTo("TEXT");
  }

  @Test
  void transformWithNullLevelHandlesGracefully() {
    ILoggingEvent event = Mockito.mock(ILoggingEvent.class);
    Mockito.when(event.getLevel()).thenReturn(null);

    String result = converter.transform(event, "TEXT");

    assertThat(result).isEqualTo("TEXT");
  }

  @Test
  void patternLayoutAppliesColorToIconAndLevel() {
    LoggerContext context = new LoggerContext();
    PatternLayout layout = new PatternLayout();
    layout.setContext(context);
    layout.getDefaultConverterMap().put("clr", LevelColorConverter.class.getName());
    layout.getDefaultConverterMap().put("levelIcon", LevelIconConverter.class.getName());
    layout.setPattern("%clr(%levelIcon) %clr(%-5p)");
    layout.start();

    LoggingEvent infoEvent = new LoggingEvent();
    infoEvent.setLevel(Level.INFO);
    infoEvent.setTimeStamp(System.currentTimeMillis());

    String infoFormatted = layout.doLayout(infoEvent);
    assertThat(infoFormatted)
        .contains("\033[34mℹ")
        .contains("\033[34mINFO");

    LoggingEvent errorEvent = new LoggingEvent();
    errorEvent.setLevel(Level.ERROR);
    errorEvent.setTimeStamp(System.currentTimeMillis());

    String errorFormatted = layout.doLayout(errorEvent);
    assertThat(errorFormatted)
        .contains("\033[31m✖")
        .contains("\033[31mERROR");

    LoggingEvent warnEvent = new LoggingEvent();
    warnEvent.setLevel(Level.WARN);
    warnEvent.setTimeStamp(System.currentTimeMillis());

    String warnFormatted = layout.doLayout(warnEvent);
    assertThat(warnFormatted)
        .contains("\033[33m⚠")
        .contains("\033[33mWARN");

    layout.stop();
  }

  private static ILoggingEvent eventWithLevel(Level level) {
    ILoggingEvent event = Mockito.mock(ILoggingEvent.class);
    Mockito.when(event.getLevel()).thenReturn(level);
    return event;
  }
}
