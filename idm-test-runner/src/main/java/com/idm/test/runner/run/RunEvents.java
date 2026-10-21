package com.idm.test.runner.run;

import com.idm.test.engine.result.ScenarioResult;
import com.idm.test.engine.spi.Message;
import com.idm.test.engine.spi.RunListener;

import java.util.List;
import java.util.Locale;

/**
 * Hears a run as {@link RunService} runs it: the run, each scenario, each step (the engine's
 * {@link RunListener} events) and the end. Every text and message delivered is already masked with
 * the run's secret values, as the reports are. Scenario names are workspace-relative paths, as
 * {@code idmtest run} prints them.
 * <p>
 * A run ends with exactly one of {@link #runFinished} or {@link #refused}: refused before any
 * scenario when the run cannot start (settings, guards, drivers), or after some when it cannot go on.
 * A scenario that did not load or validate gets {@link #scenarioFinished} without
 * {@link #scenarioStarted}. Called on the thread running the service, in order; every method does
 * nothing by default. The overloads with more arguments are what the service calls; by default they
 * hand on to the shorter ones.
 */
public interface RunEvents extends RunListener {

    /**
     * The run has its folder {@code reports/<runId>/}: the first thing heard of a run that gets one,
     * before Spring starts (the workbench answers a run request with this id).
     */
    default void runFolder(String runId) {
    }

    /** The run is about to run {@code scenarios}, in this order, in the folder {@code runId}. */
    default void runStarted(String runId, List<String> scenarios) {
    }

    /** {@link #runStarted(String, List)}, with the language the run's messages are in. */
    default void runStarted(String runId, List<String> scenarios, Locale locale) {
        runStarted(runId, scenarios);
    }

    default void scenarioStarted(String name) {
    }

    /** A scenario ended, passed, failed, stopped or not loaded; its result is masked. */
    default void scenarioFinished(ScenarioResult result) {
    }

    /**
     * {@link #scenarioFinished(ScenarioResult)}, with the failure in one line as {@code idmtest run}
     * prints it (in the run's language, masked), or null when it passed.
     */
    default void scenarioFinished(ScenarioResult result, String failureLine) {
        scenarioFinished(result);
    }

    default void runFinished(RunOutcome outcome) {
    }

    /** The run could not start, or could not go on. */
    default void refused(Message reason) {
    }

    /**
     * {@link #refused(Message)}, with the reason as {@code idmtest run} prints it (in the language
     * known at that point, masked) and, for an unexpected exception, its masked stack trace, or null.
     */
    default void refused(Message reason, String text, String stackTrace) {
        refused(reason);
    }

    /** Something to know that does not stop the run, such as {@code secrets.env} tracked by Git. */
    default void warning(Message warning, String text) {
    }
}
