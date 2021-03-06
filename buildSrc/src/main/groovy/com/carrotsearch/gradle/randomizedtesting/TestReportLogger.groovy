package com.carrotsearch.gradle.randomizedtesting

import com.carrotsearch.ant.tasks.junit4.JUnit4
import com.carrotsearch.ant.tasks.junit4.Pluralize
import com.carrotsearch.ant.tasks.junit4.TestsSummaryEventListener
import com.carrotsearch.ant.tasks.junit4.dependencies.com.google.common.base.Strings
import com.carrotsearch.ant.tasks.junit4.dependencies.com.google.common.eventbus.Subscribe
import com.carrotsearch.ant.tasks.junit4.events.*
import com.carrotsearch.ant.tasks.junit4.events.aggregated.*
import com.carrotsearch.ant.tasks.junit4.events.mirrors.FailureMirror
import com.carrotsearch.ant.tasks.junit4.listeners.AggregatedEventListener
import com.carrotsearch.ant.tasks.junit4.listeners.StackTraceFilter
import org.apache.tools.ant.filters.TokenFilter
import org.gradle.api.logging.LogLevel
import org.gradle.api.logging.Logger
import org.junit.runner.Description

import java.util.concurrent.atomic.AtomicInteger

import static com.carrotsearch.ant.tasks.junit4.FormattingUtils.*

class TestReportLogger extends TestsSummaryEventListener implements AggregatedEventListener {

    static final String FAILURE_MARKER = " <<< FAILURES!"

    /** Status names column. */
    static EnumMap<TestStatus, String> statusNames;
    static {
        statusNames = new EnumMap<>(TestStatus.class);
        for (TestStatus s : TestStatus.values()) {
            statusNames.put(s,
                    s == TestStatus.IGNORED_ASSUMPTION
                            ? "IGNOR/A" : s.toString());
        }
    }

    JUnit4 owner

    /** Logger to write the report to */
    Logger logger

    TestLoggingConfiguration config

    /** Forked concurrent JVM count. */
    int forkedJvmCount

    /** Format line for JVM ID string. */
    String jvmIdFormat

    /** Summarize the first N failures at the end. */
    int showNumFailuresAtEnd = 3

    /** Output stream that logs messages to the given logger */
    LoggingOutputStream outStream
    LoggingOutputStream errStream

    /** Display mode for output streams. */
    static enum OutputMode {
        /** Always display the output emitted from tests. */
        ALWAYS,
        /**
         * Display the output only if a test/ suite failed. This requires internal buffering
         * so the output will be shown only after a test completes.
         */
        ONERROR,
        /** Don't display the output, even on test failures. */
        NEVER
    }
    OutputMode outputMode = OutputMode.ONERROR

    /** A list of failed tests, if to be displayed at the end. */
    List<Description> failedTests = new ArrayList<>()

    /** Stack trace filters. */
    StackTraceFilter stackFilter = new StackTraceFilter()

    Map<String, Long> suiteTimes = new HashMap<>()
    boolean slowTestsFound = false

    int totalSuites
    AtomicInteger suitesCompleted = new AtomicInteger()

    @Subscribe
    void onStart(AggregatedStartEvent e) throws IOException {
        this.totalSuites = e.getSuiteCount();
        StringBuilder info = new StringBuilder('==> Test Info: ')
        info.append('seed=' + owner.getSeed() + '; ')
        info.append(Pluralize.pluralize(e.getSlaveCount(), 'jvm') + '=' + e.getSlaveCount() + '; ')
        info.append(Pluralize.pluralize(e.getSuiteCount(), 'suite') + '=' + e.getSuiteCount())
        logger.lifecycle(info.toString())

        forkedJvmCount = e.getSlaveCount();
        jvmIdFormat = " J%-" + (1 + (int) Math.floor(Math.log10(forkedJvmCount))) + "d";

        outStream = new LoggingOutputStream(logger: logger, level: LogLevel.ERROR, prefix: "  1> ")
        errStream = new LoggingOutputStream(logger: logger, level: LogLevel.ERROR, prefix: "  2> ")

        for (String contains : config.stackTraceFilters.contains) {
            TokenFilter.ContainsString containsFilter = new TokenFilter.ContainsString()
            containsFilter.setContains(contains)
            stackFilter.addContainsString(containsFilter)
        }
        for (String pattern : config.stackTraceFilters.patterns) {
            TokenFilter.ContainsRegex regexFilter = new TokenFilter.ContainsRegex()
            regexFilter.setPattern(pattern)
            stackFilter.addContainsRegex(regexFilter)
        }
    }

    @Subscribe
    void onChildBootstrap(ChildBootstrap e) throws IOException {
        logger.info("Started J" + e.getSlave().id + " PID(" + e.getSlave().getPidString() + ").");
    }

    @Subscribe
    void onHeartbeat(HeartBeatEvent e) throws IOException {
        logger.warn("HEARTBEAT J" + e.getSlave().id + " PID(" + e.getSlave().getPidString() + "): " +
                formatTime(e.getCurrentTime()) + ", stalled for " +
                formatDurationInSeconds(e.getNoEventDuration()) + " at: " +
                (e.getDescription() == null ? "<unknown>" : formatDescription(e.getDescription())))
        slowTestsFound = true
    }

    @Subscribe
    void onQuit(AggregatedQuitEvent e) throws IOException {
        if (showNumFailuresAtEnd > 0 && !failedTests.isEmpty()) {
            List<Description> sublist = this.failedTests
            StringBuilder b = new StringBuilder()
            b.append('Tests with failures')
            if (sublist.size() > showNumFailuresAtEnd) {
                sublist = sublist.subList(0, showNumFailuresAtEnd)
                b.append(" (first " + showNumFailuresAtEnd + " out of " + failedTests.size() + ")")
            }
            b.append(':\n')
            for (Description description : sublist) {
                b.append("  - ").append(formatDescription(description, true)).append('\n')
            }
            logger.warn(b.toString())
        }
        if (config.slowTests.summarySize > 0) {
            List<Map.Entry<String, Long>> sortedSuiteTimes = new ArrayList<>(suiteTimes.entrySet())
            Collections.sort(sortedSuiteTimes, new Comparator<Map.Entry<String, Long>>() {
                @Override
                int compare(Map.Entry<String, Long> o1, Map.Entry<String, Long> o2) {
                    return o2.value - o1.value // sort descending
                }
            })
            LogLevel level = slowTestsFound ? LogLevel.WARN : LogLevel.INFO
            int numToLog = Math.min(config.slowTests.summarySize, sortedSuiteTimes.size())
            logger.log(level, 'Slow Tests Summary:')
            for (int i = 0; i < numToLog; ++i) {
                logger.log(level, String.format(Locale.ENGLISH, '%6.2fs | %s',
                        sortedSuiteTimes.get(i).value / 1000.0,
                        sortedSuiteTimes.get(i).key));
            }
            logger.log(level, '') // extra vertical separation
        }
        if (failedTests.isEmpty()) {
            // summary is already printed for failures
            logger.lifecycle('==> Test Summary: ' + getResult().toString())
        }
    }

    @Subscribe
    void onSuiteStart(AggregatedSuiteStartedEvent e) throws IOException {
        if (isPassthrough()) {
            SuiteStartedEvent evt = e.getSuiteStartedEvent();
            emitSuiteStart(LogLevel.INFO, evt.getDescription());
        }
    }

    @Subscribe
    void onOutput(PartialOutputEvent e) throws IOException {
        if (isPassthrough() && logger.isInfoEnabled()) {
            // We only allow passthrough output if there is one JVM.
            switch (e.getEvent().getType()) {
                case EventType.APPEND_STDERR:
                    ((IStreamEvent) e.getEvent()).copyTo(errStream);
                    break;
                case EventType.APPEND_STDOUT:
                    ((IStreamEvent) e.getEvent()).copyTo(outStream);
                    break;
                default:
                    break;
            }
        }
    }

    @Subscribe
    void onTestResult(AggregatedTestResultEvent e) throws IOException {
        if (isPassthrough() && e.getStatus() != TestStatus.OK) {
            flushOutput();
            emitStatusLine(LogLevel.ERROR, e, e.getStatus(), e.getExecutionTime());
        }

        if (!e.isSuccessful()) {
            failedTests.add(e.getDescription());
        }
    }

    @Subscribe
    void onSuiteResult(AggregatedSuiteResultEvent e) throws IOException {
        try {
        final int completed = suitesCompleted.incrementAndGet();

        if (e.isSuccessful() && e.getTests().isEmpty()) {
            return;
        }
        if (config.slowTests.summarySize > 0) {
            suiteTimes.put(e.getDescription().getDisplayName(), e.getExecutionTime())
        }

        LogLevel level = e.isSuccessful() ? LogLevel.INFO : LogLevel.ERROR
        // We must emit buffered test and stream events (in case of failures).
        if (!isPassthrough()) {
            emitSuiteStart(level, e.getDescription())
            emitBufferedEvents(level, e)
        }

        // Emit a synthetic failure for suite-level errors, if any.
        if (!e.getFailures().isEmpty()) {
            emitStatusLine(level, e, TestStatus.ERROR, 0)
        }

        if (!e.getFailures().isEmpty()) {
            failedTests.add(e.getDescription())
        }

        emitSuiteEnd(level, e, completed)
    } catch (Exception exc) {
            logger.lifecycle('EXCEPTION: ', exc)
        }
    }

    /** Suite prologue. */
    void emitSuiteStart(LogLevel level, Description description) throws IOException {
        logger.log(level, 'Suite: ' + description.getDisplayName());
    }

    void emitBufferedEvents(LogLevel level, AggregatedSuiteResultEvent e) throws IOException {
        if (outputMode == OutputMode.NEVER) {
            return
        }

        final IdentityHashMap<TestFinishedEvent,AggregatedTestResultEvent> eventMap = new IdentityHashMap<>();
        for (AggregatedTestResultEvent tre : e.getTests()) {
            eventMap.put(tre.getTestFinishedEvent(), tre)
        }

        final boolean emitOutput = outputMode == OutputMode.ALWAYS && isPassthrough() == false ||
                                   outputMode == OutputMode.ONERROR && e.isSuccessful() == false

        for (IEvent event : e.getEventStream()) {
            switch (event.getType()) {
                case EventType.APPEND_STDOUT:
                    if (emitOutput) ((IStreamEvent) event).copyTo(outStream);
                    break;

                case EventType.APPEND_STDERR:
                    if (emitOutput) ((IStreamEvent) event).copyTo(errStream);
                    break;

                case EventType.TEST_FINISHED:
                    assert eventMap.containsKey(event)
                    final AggregatedTestResultEvent aggregated = eventMap.get(event);
                    if (aggregated.getStatus() != TestStatus.OK) {
                        flushOutput();
                        emitStatusLine(level, aggregated, aggregated.getStatus(), aggregated.getExecutionTime());
                    }

                default:
                    break;
            }
        }

        if (emitOutput) {
            flushOutput()
        }
    }

    void emitSuiteEnd(LogLevel level, AggregatedSuiteResultEvent e, int suitesCompleted) throws IOException {

        final StringBuilder b = new StringBuilder();
        b.append(String.format(Locale.ENGLISH, 'Completed [%d/%d]%s in %.2fs, ',
                suitesCompleted,
                totalSuites,
                e.getSlave().slaves > 1 ? ' on J' + e.getSlave().id : '',
                e.getExecutionTime() / 1000.0d));
        b.append(e.getTests().size()).append(Pluralize.pluralize(e.getTests().size(), ' test'));

        int failures = e.getFailureCount();
        if (failures > 0) {
            b.append(', ').append(failures).append(Pluralize.pluralize(failures, ' failure'));
        }

        int errors = e.getErrorCount();
        if (errors > 0) {
            b.append(', ').append(errors).append(Pluralize.pluralize(errors, ' error'));
        }

        int ignored = e.getIgnoredCount();
        if (ignored > 0) {
            b.append(', ').append(ignored).append(' skipped');
        }

        if (!e.isSuccessful()) {
            b.append(' <<< FAILURES!');
        }

        b.append('\n')
        logger.log(level, b.toString());
    }

    /** Emit status line for an aggregated event. */
    void emitStatusLine(LogLevel level, AggregatedResultEvent result, TestStatus status, long timeMillis) throws IOException {
        final StringBuilder line = new StringBuilder();

        line.append(Strings.padEnd(statusNames.get(status), 8, ' ' as char))
        line.append(formatDurationInSeconds(timeMillis))
        if (forkedJvmCount > 1) {
            line.append(String.format(Locale.ENGLISH, jvmIdFormat, result.getSlave().id))
        }
        line.append(' | ')

        line.append(formatDescription(result.getDescription()))
        if (!result.isSuccessful()) {
            line.append(FAILURE_MARKER)
        }
        logger.log(level, line.toString())

        PrintWriter writer = new PrintWriter(new LoggingOutputStream(logger: logger, level: level, prefix: '   > '))

        if (status == TestStatus.IGNORED && result instanceof AggregatedTestResultEvent) {
            writer.write('Cause: ')
            writer.write(((AggregatedTestResultEvent) result).getCauseForIgnored())
            writer.flush()
        }

        final List<FailureMirror> failures = result.getFailures();
        if (!failures.isEmpty()) {
            int count = 0;
            for (FailureMirror fm : failures) {
                count++;
                if (fm.isAssumptionViolation()) {
                    writer.write(String.format(Locale.ENGLISH,
                            'Assumption #%d: %s',
                            count, fm.getMessage() == null ? '(no message)' : fm.getMessage()));
                } else {
                    writer.write(String.format(Locale.ENGLISH,
                            'Throwable #%d: %s',
                            count,
                            stackFilter.apply(fm.getTrace())));
                }
            }
            writer.flush()
        }
    }

    void flushOutput() throws IOException {
        outStream.flush()
        errStream.flush()
    }

    /** Returns true if output should be logged immediately. Only relevant when running with INFO log level. */
    boolean isPassthrough() {
        return forkedJvmCount == 1 && outputMode == OutputMode.ALWAYS && logger.isInfoEnabled()
    }

    @Override
    void setOuter(JUnit4 task) {
        owner = task
    }
}
