package com.idm.test.runner.run;

import com.idm.test.connector.database.client.MultiDatabaseConnector;
import com.idm.test.connector.database.config.DatabaseProperties;
import com.idm.test.connector.ldap.client.LdapDirectories;
import com.idm.test.connector.rest.client.IdmRestConnector;
import com.idm.test.connector.rest.client.RestAuthenticator;
import com.idm.test.connector.rest.config.RestProperties;
import com.idm.test.connector.soap.client.ResourceServiceConnector;
import com.idm.test.connector.soap.client.RoleServiceConnector;
import com.idm.test.connector.soap.config.SoapProperties;
import com.idm.test.core.config.TestExecutorProperties;
import com.idm.test.core.engine.TestExecutor;
import com.idm.test.engine.ScenarioEngine;
import com.idm.test.engine.ScenarioLoader;
import com.idm.test.engine.ScenarioValidator;
import com.idm.test.engine.ValidationException;
import com.idm.test.engine.i18n.Messages;
import com.idm.test.engine.i18n.ReportLocale;
import com.idm.test.engine.model.Scenario;
import com.idm.test.engine.result.CleanupResult;
import com.idm.test.engine.result.RunInfo;
import com.idm.test.engine.result.ScenarioResult;
import com.idm.test.engine.result.StepResult;
import com.idm.test.engine.spi.FailureKind;
import com.idm.test.engine.spi.Message;
import com.idm.test.engine.spi.RunListener;
import com.idm.test.engine.spi.StepType;
import com.idm.test.runner.DriverCheck;
import com.idm.test.runner.DriverLoader;
import com.idm.test.runner.ExitCodes;
import com.idm.test.runner.GitTracking;
import com.idm.test.runner.RunLog;
import com.idm.test.runner.RunnerApplication;
import com.idm.test.runner.RunnerSettings;
import com.idm.test.runner.SecretMask;
import com.idm.test.runner.SecretsEnv;
import com.idm.test.runner.SecretsInitializer;
import com.idm.test.runner.UsageException;
import com.idm.test.runner.ValidateCommand;
import com.idm.test.runner.Workspace;
import com.idm.test.utils.data.TestDataGenerator;
import com.idm.test.wiring.DirectoryScenarioSource;
import com.idm.test.wiring.EngineFactory;
import com.idm.test.wiring.RealEnvironmentGuards;
import com.idm.test.wiring.RunInfos;
import com.idm.test.wiring.RunRecorder;
import com.idm.test.wiring.ScenarioNeeds;
import com.idm.test.wiring.StepRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.logging.LoggingApplicationListener;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Runs a workspace's scenarios against the systems of a profile, telling a {@link RunEvents} as it
 * goes: {@code idmtest run} and the workbench both run through it. The order is fixed: workspace and
 * profile file, {@code secrets.env}, the Git warning, the run folder and its log, the workspace's
 * JDBC drivers, Spring, scenario selection, loading and validation, the driver check, then the
 * guards; only then does any scenario run. The context starts lazily, so the guards read settings
 * before any connector is even built.
 * <p>
 * Every text the events carry, and every result written, is masked with the run's secret values.
 * A stop requested between scenarios starts no more; one requested during a scenario is the
 * engine's to notice, and the scenario's cleanup does not run.
 */
public final class RunService {

    private static final Logger LOG = LoggerFactory.getLogger(RunService.class);

    private static final String RESULTS_FILE = "results.json";
    /** Concatenated so CatalogTest, which reads {@code "report.*"} literals as message keys, skips it. */
    private static final String REPORT_FILE = "report" + ".html";

    private final Clock clock;
    private final boolean verbose;
    /** Applies a run's logging settings in a process that has its own logging (the workbench); null for Spring Boot's. */
    private final Consumer<Environment> hostLogging;

    /** A service on the system clock whose run log stays off the console. */
    public RunService() {
        this(Clock.systemDefaultZone(), false);
    }

    /**
     * @param clock   names the run folders
     * @param verbose whether the console echoes the (masked) run log, as {@code --verbose} does
     */
    public RunService(Clock clock, boolean verbose) {
        this(clock, verbose, null);
    }

    /**
     * @param hostLogging when not null, a run does not let Spring Boot initialise logging — that resets
     *                    the process's Logback, under a host such as the workbench serving requests —
     *                    and this applies the run's logging settings instead, from the run's environment,
     *                    once it is prepared; null (the CLI) leaves logging to Spring Boot
     */
    public RunService(Clock clock, boolean verbose, Consumer<Environment> hostLogging) {
        this.clock = clock;
        this.verbose = verbose;
        this.hostLogging = hostLogging;
    }

    /**
     * Runs {@code request}; {@code stopRequested} is polled between scenarios and by the engine
     * during each one. Returns how it ended, which {@link RunEvents#runFinished} also hears when
     * the run got to the end.
     */
    public RunOutcome run(RunRequest request, RunEvents listener, BooleanSupplier stopRequested) {
        // A listener that throws (a browser gone mid-run) never changes the run or what it writes.
        RunEvents events = new Guarded(listener);
        Map<String, String> environment = request.environmentOverride();
        Map<String, String> variables = environment == null ? System.getenv() : environment;
        // One language for the run: the request's, then IDM_TEST_LOCALE, then -Didm.test.locale, then
        // the profile's idm.test.report.locale, then English. The first three are known now and are
        // handed to Spring as the command-line idm.test.report.locale, so the events and the report
        // agree; only messages before Spring starts cannot see the profile file.
        String localeTag = request.locale() != null ? request.locale().toLanguageTag()
                : firstNonBlank(variables.get("IDM_TEST_LOCALE"), System.getProperty("idm.test.locale"));
        Locale early;
        try {
            early = ReportLocale.resolve(localeTag);
        } catch (ReportLocale.UnsupportedLocaleException e) {
            return refused(events, Message.of("engine.text", e.getMessage()), e.getMessage(), null, null);
        }
        if (request.profile() == null) {
            Message reason = Message.of("runner.run.needsProfile");
            return refused(events, reason, Messages.render(reason, early), null, null);
        }

        Workspace workspace;
        Map<String, String> secrets;
        try {
            workspace = Workspace.open(request.workspace());
            Workspace.requireProfile(workspace, request.profile());
            secrets = SecretsEnv.read(workspace.secretsFile());
        } catch (UsageException e) {
            return refused(events, e.detail(), Messages.render(e.detail(), early), null, null);
        }
        if (Files.exists(workspace.secretsFile()) && GitTracking.isTracked(workspace.root(), workspace.secretsFile())) {
            Message warning = GitTracking.trackedWarning();
            events.warning(warning, Messages.render(warning, early));
        }
        // The values the run will use, environment variables included, not only the file's.
        SecretMask mask = SecretMask.of(secrets, variables);

        Path folder = workspace.newRunFolder(request.profile(), clock);
        events.runFolder(folder.getFileName().toString());
        Context run = new Context(workspace, request, secrets, folder, mask, events, stopRequested, variables);
        try (RunLog runLog = RunLog.start(folder.resolve("run.log"), verbose, mask)) {
            LOG.info("idmtest run: workspace {}, profile {}", workspace.root(), request.profile());
            Thread thread = Thread.currentThread();
            ClassLoader previousLoader = thread.getContextClassLoader();
            DriverLoader.Drivers drivers;
            try {
                // Before Spring starts, so every pool the context builds can reach the drivers.
                drivers = DriverLoader.open(workspace.drivers(), previousLoader);
            } catch (RuntimeException | Error e) {
                return run.startFailed(e, early);
            }
            List<Path> jars = new ArrayList<>(DriverLoader.installJars(System.getProperty("loader.path")));
            jars.addAll(drivers.jars());
            LOG.info("driver jars loaded: {}", jars);
            try (drivers) {
                if (drivers.loader() != null) {
                    // A pool loads a driver-class-name through the context class loader.
                    thread.setContextClassLoader(drivers.loader());
                }
                return run.startAndRun(localeTag, runLog, early);
            } finally {
                thread.setContextClassLoader(previousLoader);
            }
        }
    }

    /**
     * Hands the run's prepared environment to {@link #hostLogging}: a class, not a lambda, so Spring sees
     * the event type.
     */
    private ApplicationListener<ApplicationEnvironmentPreparedEvent> hostLoggingListener() {
        return new ApplicationListener<>() {
            @Override
            public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
                hostLogging.accept(event.getEnvironment());
            }
        };
    }

    /** A refusal before the run has a folder. */
    private static RunOutcome refused(RunEvents events, Message reason, String text, String trace, Path folder) {
        events.refused(reason, text, trace);
        return new RunOutcome(folder == null ? null : folder.getFileName().toString(), folder, 0, 0, 0, 0, false,
                ExitCodes.CANNOT_START);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return null;
    }

    /** The deepest cause's first line: the part of a Spring start-up failure that names the problem. */
    private static String describe(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        if (message == null || message.isBlank()) {
            return root.getClass().getSimpleName();
        }
        return message.lines().findFirst().orElse(message).strip();
    }

    /**
     * The command-line arguments for Spring: the profile and the run's language, with lazy
     * initialisation and the logging outputs pinned so that no configuration file can change them. A
     * lazy context builds no connector before the guards have read the settings.
     */
    private static String[] springArgs(String profile, String localeTag) {
        List<String> args = new ArrayList<>(List.of(RunnerSettings.commandLineArgs(profile, localeTag)));
        args.add("--spring.main.lazy-initialization=true");
        // The run's masked run.log is the only log output: no settings file may add a log file
        // or a Logback configuration of its own, which would receive secrets.env values unmasked.
        args.add("--logging.file.name=");
        args.add("--logging.file.path=");
        args.add("--logging.config=");
        return args.toArray(String[]::new);
    }

    /** One run, from its folder to the outcome. */
    private final class Context {

        private final Workspace workspace;
        private final RunRequest request;
        private final Map<String, String> secrets;
        private final Path folder;
        private final String runId;
        private final SecretMask mask;
        private final RunEvents events;
        private final BooleanSupplier stopRequested;
        private final Map<String, String> variables;
        private ConfigurableApplicationContext context;

        Context(Workspace workspace, RunRequest request, Map<String, String> secrets, Path folder, SecretMask mask, RunEvents events,
                BooleanSupplier stopRequested, Map<String, String> variables) {
            this.workspace = workspace;
            this.request = request;
            this.secrets = secrets;
            this.folder = folder;
            this.runId = folder.getFileName().toString();
            this.mask = mask;
            this.events = events;
            this.stopRequested = stopRequested;
            this.variables = variables;
        }

        /** The run cannot start: the reason masked, and the masked stack trace; exit 2. */
        RunOutcome startFailed(Throwable e, Locale locale) {
            LOG.error("The run could not start", e);
            Message reason = Message.of("runner.run.startFailed", mask.apply(describe(e)));
            return refusedHere(reason, Messages.render(reason, locale), mask.stackTrace(e));
        }

        RunOutcome refusedHere(Message reason, String text, String trace) {
            return refused(events, reason, text, trace, folder);
        }

        /**
         * Starts Spring with the run's settings and runs the scenarios; the context is closed after.
         * The request's environment, when not null, replaces the real environment variables in Spring.
         */
        RunOutcome startAndRun(String localeTag, RunLog runLog, Locale early) {
            Map<String, String> fakeEnvironment = request.environmentOverride();
            try {
                SpringApplicationBuilder builder = new SpringApplicationBuilder(RunnerApplication.class)
                        .web(WebApplicationType.NONE)
                        .lazyInitialization(true)
                        .registerShutdownHook(false)
                        .properties(RunnerSettings.defaultProperties(workspace))
                        .initializers(new SecretsInitializer(secrets))
                        .listeners(runLog.listener());
                if (hostLogging != null) {
                    SpringApplication application = builder.application();
                    List<ApplicationListener<?>> listeners = new ArrayList<>(application.getListeners());
                    listeners.removeIf(LoggingApplicationListener.class::isInstance);
                    listeners.add(hostLoggingListener());
                    application.setListeners(listeners);
                }
                if (fakeEnvironment != null) {
                    builder.initializers(started -> started.getEnvironment().getPropertySources().replace(
                            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                            new SystemEnvironmentPropertySource(
                                    StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                                    new LinkedHashMap<String, Object>(fakeEnvironment))));
                }
                context = builder.run(springArgs(request.profile(), localeTag));
            } catch (RuntimeException | Error e) {
                return startFailed(e, early);
            }
            try (ConfigurableApplicationContext started = context) {
                return execute();
            }
        }

        private RunOutcome execute() {
            Environment environment = context.getEnvironment();
            Locale locale;
            try {
                locale = RunInfos.locale(environment, variables.get("IDM_TEST_LOCALE"),
                        System.getProperty("idm.test.locale"));
            } catch (ReportLocale.UnsupportedLocaleException e) {
                String text = mask.apply(e.getMessage());
                return refusedHere(Message.of("engine.text", text), text, null);
            }
            DirectoryScenarioSource source = new DirectoryScenarioSource(workspace.scenarios());
            List<String> names;
            Loaded loaded;
            try {
                names = select(source);
                loaded = load(source, names);
                checkDrivers(loaded);
                requireNotEmbedded(environment);
                RealEnvironmentGuards.check(environment, source, names, locale,
                        context.getBean(RestProperties.class), context.getBean(DatabaseProperties.class),
                        soapProperties(environment));
            } catch (UsageException e) {
                String message = mask.apply(Messages.render(e.detail(), locale));
                LOG.error("The run could not start: {}", message);
                return refusedHere(mask.message(e.detail()), message, null);
            } catch (RealEnvironmentGuards.Refusal e) {
                String message = mask.apply(e.getMessage());
                LOG.error("Refused: {}", message);
                Message reason = e.detail() == null ? Message.of("engine.text", message) : mask.message(e.detail());
                return refusedHere(reason, message, null);
            } catch (IOException | RuntimeException e) {
                return startFailed(e, locale);
            }

            ScenarioEngine engine;
            RunInfo runInfo;
            try {
                engine = engine();
                runInfo = mask.runInfo(runInfo(environment, loaded));
            } catch (ReportLocale.UnsupportedLocaleException e) {
                String text = mask.apply(e.getMessage());
                return refusedHere(Message.of("engine.text", text), text, null);
            } catch (RuntimeException e) {
                return startFailed(e, locale);
            }
            Tally tally = new Tally(names.size());
            try {
                return runAll(names, loaded, engine, runInfo, locale, tally);
            } catch (RuntimeException e) {
                // Scenarios have run: what was written so far stays, and the run counts as failed.
                LOG.error("The run stopped", e);
                Message reason = Message.of("runner.run.stopped", mask.apply(describe(e)));
                events.refused(reason, Messages.render(reason, locale), mask.stackTrace(e));
                return tally.outcome(ExitCodes.FAILED);
            }
        }

        /** The scenarios the request's patterns select, each once, in order; all of them for none. */
        private List<String> select(DirectoryScenarioSource source) throws IOException {
            if (request.scenarios().isEmpty()) {
                return ValidateCommand.select(source, null);
            }
            Set<String> names = new LinkedHashSet<>();
            for (String pattern : request.scenarios()) {
                names.addAll(ValidateCommand.select(source, pattern));
            }
            return List.copyOf(names);
        }

        /**
         * Guard 1 in the runner's words: the user chose the profile with {@code --profile}, not with
         * the JUnit runners' {@code IDM_TEST_PROFILE}.
         */
        private static void requireNotEmbedded(Environment environment) {
            List<String> profiles = List.of(environment.getActiveProfiles());
            if (profiles.contains(RealEnvironmentGuards.EMBEDDED_PROFILE)) {
                throw new UsageException(Message.of("runner.guard.embeddedProfile", profiles.toString(),
                        RealEnvironmentGuards.EMBEDDED_PROFILE));
            }
        }

        /**
         * The SOAP settings for the guards. {@code SoapProperties} is a bean only when
         * {@code idm.test.soap.url} is set, so without it the settings are bound here, and the
         * guards still see a half-given credential.
         */
        private SoapProperties soapProperties(Environment environment) {
            return context.getBeanProvider(SoapProperties.class).getIfAvailable(() -> Binder.get(environment)
                    .bind("idm.test.soap", SoapProperties.class).orElseGet(SoapProperties::new));
        }

        /** Every database the selected scenarios use must have a JDBC driver, before any scenario runs. */
        private void checkDrivers(Loaded loaded) {
            ScenarioNeeds needs = ScenarioNeeds.of(List.copyOf(loaded.scenarios().values()));
            DriverCheck.check(context.getBean(DatabaseProperties.class), needs.databases(), workspace.drivers());
        }

        private RunOutcome runAll(List<String> names, Loaded loaded, ScenarioEngine engine, RunInfo runInfo,
                                  Locale locale, Tally tally) {
            events.runStarted(runId, names, locale);
            RunListener listener = masked(events);
            List<ScenarioResult> results = new ArrayList<>();
            for (String name : names) {
                if (stopRequested.getAsBoolean()) {
                    tally.stopped = true;
                    break;
                }
                ScenarioResult result;
                String failureLine;
                Scenario scenario = loaded.scenarios().get(name);
                if (scenario == null) {
                    result = mask.result(loaded.failures().get(name));
                    Message detail = result.failureDetailMessage();
                    failureLine = mask.apply(detail != null ? Messages.render(detail, locale) : result.failureDetail());
                } else {
                    events.scenarioStarted(name);
                    result = mask.result(runOne(engine, scenario, name, listener));
                    failureLine = result.success() ? null : mask.apply(result.failureSummary(locale));
                }
                LOG.info("{} {}", result.success() ? "passed" : "failed", name);
                tally.count(result);
                // Each result is masked like the events: the reports are the files people share. Written
                // before the listener hears it, so what it does then cannot keep a result out of them.
                results.add(result);
                RunRecorder.write(results, folder.resolve(RESULTS_FILE), folder.resolve(REPORT_FILE), runInfo);
                events.scenarioFinished(result, failureLine);
                if (result.stopped()) {
                    tally.stopped = true;
                    break;
                }
            }
            RunOutcome outcome = tally.outcome(tally.passed == names.size() ? ExitCodes.OK : ExitCodes.FAILED);
            events.runFinished(outcome);
            return outcome;
        }

        /** The engine's events, every text in them masked before {@code events} hears it. */
        private RunListener masked(RunEvents target) {
            return new RunListener() {
                @Override
                public void stepStarted(int index, String type, String description) {
                    target.stepStarted(index, type, mask.apply(description));
                }

                @Override
                public void attempt(int index, int attempt, long elapsedMillis, String message) {
                    target.attempt(index, attempt, elapsedMillis, mask.apply(message));
                }

                @Override
                public void stepFinished(StepResult result) {
                    target.stepFinished(mask.step(result));
                }

                @Override
                public void cleanupFinished(CleanupResult result) {
                    target.cleanupFinished(mask.cleanup(result));
                }
            };
        }

        /** The engine's result; an unexpected exception becomes a failed result, and the run goes on. */
        private ScenarioResult runOne(ScenarioEngine engine, Scenario scenario, String name, RunListener listener) {
            long start = System.nanoTime();
            try {
                return engine.run(scenario, name, listener, stopRequested);
            } catch (RuntimeException e) {
                LOG.error("Scenario {} stopped unexpectedly", name, e);
                long millis = (System.nanoTime() - start) / 1_000_000;
                return new ScenarioResult(name, name, false, millis, List.of(), List.of(),
                        mask.apply(e.toString()), null);
            }
        }

        /** Loads and validates each scenario; a failure becomes a failed result, as the JUnit runners do. */
        private Loaded load(DirectoryScenarioSource source, List<String> names) {
            ScenarioValidator validator = EngineFactory.validator(StepRegistry.forValidation());
            Map<String, Scenario> scenarios = new LinkedHashMap<>();
            Map<String, ScenarioResult> failures = new LinkedHashMap<>();
            for (String name : names) {
                try (InputStream in = source.open(name)) {
                    Scenario scenario = ScenarioLoader.load(in, name);
                    validator.validate(scenario, name);
                    scenarios.put(name, scenario);
                } catch (IOException | RuntimeException e) {
                    Message detail = e instanceof ValidationException validation ? validation.detail() : null;
                    String message = e instanceof IOException ? e.toString() : e.getMessage();
                    failures.put(name, new ScenarioResult(name, name, false, 0L, List.of(), List.of(), message,
                            null, detail == null ? null : detail.key(), detail == null ? null : detail.args()));
                }
            }
            return new Loaded(scenarios, failures);
        }

        private ScenarioEngine engine() {
            Map<String, StepType> registry = StepRegistry.of(
                    context.getBean(LdapDirectories.class),
                    context.getBean(MultiDatabaseConnector.class),
                    context.getBeanProvider(RoleServiceConnector.class).getIfAvailable(),
                    context.getBeanProvider(ResourceServiceConnector.class).getIfAvailable(),
                    context.getBean(IdmRestConnector.class),
                    context.getBean(RestProperties.class),
                    context.getBean(RestAuthenticator.class));
            return EngineFactory.engine(registry, context.getBean(TestDataGenerator.class),
                    context.getBean(TestExecutor.class), context.getBean(TestExecutorProperties.class));
        }

        /** The report header, its filter being the request's patterns rather than the JUnit runners' property. */
        private RunInfo runInfo(Environment environment, Loaded loaded) {
            RunInfo base = RunInfos.of("runner.name.workspace", environment, List.copyOf(loaded.scenarios().values()));
            String filter = request.scenarios().isEmpty() ? null : String.join(", ", request.scenarios());
            return new RunInfo(base.runner(), base.startedAt(), base.profiles(), filter, base.endpoints(),
                    base.locale());
        }

        /** The counts so far; see {@link RunOutcome} for the rule. */
        private final class Tally {

            private final int total;
            private int passed;
            private int findings;
            private int setupProblems;
            private boolean stopped;

            Tally(int total) {
                this.total = total;
            }

            void count(ScenarioResult result) {
                if (result.success()) {
                    passed++;
                } else if (result.failureKind() == FailureKind.FINDING) {
                    findings++;
                } else {
                    setupProblems++;
                }
            }

            RunOutcome outcome(int exitCode) {
                return new RunOutcome(runId, folder, total, passed, findings, setupProblems, stopped, exitCode);
            }
        }
    }

    /**
     * Hands every event on to a listener and drops what it throws (logging the exception's class
     * only: its message could quote a value); the engine does the same for the step events.
     */
    private static final class Guarded implements RunEvents {

        private final RunEvents target;

        Guarded(RunEvents target) {
            this.target = target;
        }

        private void safely(String event, Runnable call) {
            try {
                call.run();
            } catch (RuntimeException e) {
                LOG.warn("Run listener {} threw {} at {}; ignored", target.getClass().getName(),
                        e.getClass().getName(), event);
            }
        }

        @Override
        public void runFolder(String runId) {
            safely("runFolder", () -> target.runFolder(runId));
        }

        @Override
        public void runStarted(String runId, List<String> scenarios, Locale locale) {
            safely("runStarted", () -> target.runStarted(runId, scenarios, locale));
        }

        @Override
        public void scenarioStarted(String name) {
            safely("scenarioStarted", () -> target.scenarioStarted(name));
        }

        @Override
        public void stepStarted(int index, String type, String description) {
            safely("stepStarted", () -> target.stepStarted(index, type, description));
        }

        @Override
        public void attempt(int index, int attempt, long elapsedMillis, String message) {
            safely("attempt", () -> target.attempt(index, attempt, elapsedMillis, message));
        }

        @Override
        public void stepFinished(StepResult result) {
            safely("stepFinished", () -> target.stepFinished(result));
        }

        @Override
        public void cleanupFinished(CleanupResult result) {
            safely("cleanupFinished", () -> target.cleanupFinished(result));
        }

        @Override
        public void scenarioFinished(ScenarioResult result, String failureLine) {
            safely("scenarioFinished", () -> target.scenarioFinished(result, failureLine));
        }

        @Override
        public void runFinished(RunOutcome outcome) {
            safely("runFinished", () -> target.runFinished(outcome));
        }

        @Override
        public void refused(Message reason, String text, String stackTrace) {
            safely("refused", () -> target.refused(reason, text, stackTrace));
        }

        @Override
        public void warning(Message warning, String text) {
            safely("warning", () -> target.warning(warning, text));
        }
    }

    private record Loaded(Map<String, Scenario> scenarios, Map<String, ScenarioResult> failures) {
    }
}
