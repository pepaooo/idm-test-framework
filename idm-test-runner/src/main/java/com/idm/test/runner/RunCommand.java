package com.idm.test.runner;

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
import com.idm.test.engine.result.RunInfo;
import com.idm.test.engine.result.ScenarioResult;
import com.idm.test.engine.spi.Message;
import com.idm.test.engine.spi.StepType;
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
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code idmtest run}: runs a workspace's scenarios against the systems of a profile. The order is
 * fixed: options, workspace and profile file, {@code secrets.env}, the run folder and its log, the
 * workspace's JDBC drivers, Spring, scenario selection, loading and validation, the driver check, then
 * the guards; only then does any scenario run.
 * The context starts lazily, so the guards read settings before any connector is even built.
 */
public final class RunCommand {

    private static final Logger LOG = LoggerFactory.getLogger(RunCommand.class);

    private static final String RESULTS_FILE = "results.json";
    /** Concatenated so CatalogTest, which reads {@code "report.*"} literals as message keys, skips it. */
    private static final String REPORT_FILE = "report" + ".html";

    private RunCommand() {
    }

    public static int run(CommandLine line, PrintStream out, PrintStream err, Clock clock) {
        return run(line, out, err, clock, null);
    }

    /**
     * The run, with {@code environment} standing in for the process's environment variables when it
     * is not null: tests hand in their own, and Spring then sees it in place of the real one.
     */
    static int run(CommandLine line, PrintStream out, PrintStream err, Clock clock, Map<String, String> environment) {
        Map<String, String> variables = environment == null ? System.getenv() : Map.copyOf(environment);
        // One language for the run: --locale, then IDM_TEST_LOCALE, then -Didm.test.locale, then the
        // profile's idm.test.report.locale, then English. The first three are known now and are
        // handed to Spring as the command-line idm.test.report.locale, so the console and the
        // report agree; only messages before Spring starts cannot see the profile file.
        String localeTag = firstNonBlank(line.locale(), variables.get("IDM_TEST_LOCALE"),
                System.getProperty("idm.test.locale"));
        Locale early;
        try {
            early = ReportLocale.resolve(localeTag);
        } catch (ReportLocale.UnsupportedLocaleException e) {
            if (line.profile() == null) {
                err.println(Messages.render(Message.of("runner.run.needsProfile"), Locale.ENGLISH));
            } else {
                err.println(e.getMessage());
            }
            return ExitCodes.CANNOT_START;
        }
        if (line.profile() == null) {
            err.println(Messages.render(Message.of("runner.run.needsProfile"), early));
            return ExitCodes.CANNOT_START;
        }

        Workspace workspace;
        Map<String, String> secrets;
        try {
            workspace = Workspace.open(line.workspace());
            Workspace.requireProfile(workspace, line.profile());
            secrets = SecretsEnv.read(workspace.secretsFile());
        } catch (UsageException e) {
            err.println(Messages.render(e.detail(), early));
            return ExitCodes.CANNOT_START;
        }
        if (Files.exists(workspace.secretsFile()) && GitTracking.isTracked(workspace.root(), workspace.secretsFile())) {
            err.println(Messages.render(GitTracking.trackedWarning(), early));
        }
        // The values the run will use, environment variables included, not only the file's.
        SecretMask mask = SecretMask.of(secrets, variables);

        Path folder = workspace.newRunFolder(line.profile(), clock);
        try (RunLog runLog = RunLog.start(folder.resolve("run.log"), line.verbose(), mask)) {
            LOG.info("idmtest run: workspace {}, profile {}", workspace.root(), line.profile());
            Thread thread = Thread.currentThread();
            ClassLoader previousLoader = thread.getContextClassLoader();
            DriverLoader.Drivers drivers;
            try {
                // Before Spring starts, so every pool the context builds can reach the drivers.
                drivers = DriverLoader.open(workspace.drivers(), previousLoader);
            } catch (RuntimeException | Error e) {
                return startFailed(e, line, err, early, mask);
            }
            List<Path> jars = new ArrayList<>(DriverLoader.installJars(System.getProperty("loader.path")));
            jars.addAll(drivers.jars());
            LOG.info("driver jars loaded: {}", jars);
            try (drivers) {
                if (drivers.loader() != null) {
                    // A pool loads a driver-class-name through the context class loader.
                    thread.setContextClassLoader(drivers.loader());
                }
                return startAndRun(workspace, secrets, environment, variables, folder, line, localeTag, runLog, out,
                        err, early, mask);
            } finally {
                thread.setContextClassLoader(previousLoader);
            }
        }
    }

    /**
     * Starts Spring with the run's settings and runs the scenarios; the context is closed after.
     * {@code fakeEnvironment}, when not null, replaces the real environment variables in Spring.
     */
    private static int startAndRun(Workspace workspace, Map<String, String> secrets, Map<String, String> fakeEnvironment,
                                   Map<String, String> variables, Path folder, CommandLine line, String localeTag,
                                   RunLog runLog, PrintStream out, PrintStream err, Locale early, SecretMask mask) {
        ConfigurableApplicationContext context;
        try {
            SpringApplicationBuilder builder = new SpringApplicationBuilder(RunnerApplication.class)
                    .web(WebApplicationType.NONE)
                    .lazyInitialization(true)
                    .registerShutdownHook(false)
                    .properties(RunnerSettings.defaultProperties(workspace))
                    .initializers(new SecretsInitializer(secrets))
                    .listeners(runLog.listener());
            if (fakeEnvironment != null) {
                builder.initializers(started -> started.getEnvironment().getPropertySources().replace(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                                new LinkedHashMap<String, Object>(fakeEnvironment))));
            }
            context = builder.run(springArgs(line.profile(), localeTag));
        } catch (RuntimeException | Error e) {
            return startFailed(e, line, err, early, mask);
        }
        try (context) {
            return new Run(workspace, folder, line, context, out, err, mask, variables).execute();
        }
    }

    /**
     * The command-line arguments for Spring: the profile and the run's language, with lazy
     * initialisation and the logging outputs pinned so that no configuration file can change them. A lazy context builds
     * no connector before the guards have read the settings.
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

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return null;
    }

    /** One line on {@code err} (a masked stack trace too with {@code --verbose}); exit 2. */
    private static int startFailed(Throwable e, CommandLine line, PrintStream err, Locale locale, SecretMask mask) {
        LOG.error("The run could not start", e);
        err.println(Messages.render(Message.of("runner.run.startFailed", mask.apply(describe(e))), locale));
        if (line.verbose()) {
            err.print(mask.stackTrace(e));
        }
        return ExitCodes.CANNOT_START;
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

    /** One run, from a started context to the summary. */
    private static final class Run {

        private final Workspace workspace;
        private final Path folder;
        private final CommandLine line;
        private final ConfigurableApplicationContext context;
        private final PrintStream out;
        private final PrintStream err;
        private final SecretMask mask;
        private final Map<String, String> variables;

        Run(Workspace workspace, Path folder, CommandLine line, ConfigurableApplicationContext context,
            PrintStream out, PrintStream err, SecretMask mask, Map<String, String> variables) {
            this.workspace = workspace;
            this.folder = folder;
            this.line = line;
            this.context = context;
            this.out = out;
            this.err = err;
            this.mask = mask;
            this.variables = variables;
        }

        int execute() {
            Environment environment = context.getEnvironment();
            Locale locale;
            try {
                locale = RunInfos.locale(environment, variables.get("IDM_TEST_LOCALE"),
                        System.getProperty("idm.test.locale"));
            } catch (ReportLocale.UnsupportedLocaleException e) {
                err.println(e.getMessage());
                return ExitCodes.CANNOT_START;
            }
            DirectoryScenarioSource source = new DirectoryScenarioSource(workspace.scenarios());
            List<String> names;
            Loaded loaded;
            try {
                names = ValidateCommand.select(source, line.scenario());
                loaded = load(source, names);
                checkDrivers(loaded);
                requireNotEmbedded(environment);
                RealEnvironmentGuards.check(environment, source, names, locale,
                        context.getBean(RestProperties.class), context.getBean(DatabaseProperties.class),
                        soapProperties(environment));
            } catch (UsageException e) {
                String message = mask.apply(Messages.render(e.detail(), locale));
                LOG.error("The run could not start: {}", message);
                err.println(message);
                return ExitCodes.CANNOT_START;
            } catch (RealEnvironmentGuards.Refusal e) {
                String message = mask.apply(e.getMessage());
                LOG.error("Refused: {}", message);
                err.println(message);
                return ExitCodes.CANNOT_START;
            } catch (IOException | RuntimeException e) {
                return startFailed(e, line, err, locale, mask);
            }

            ScenarioEngine engine;
            RunInfo runInfo;
            try {
                engine = engine();
                runInfo = mask.runInfo(runInfo(environment, loaded));
            } catch (ReportLocale.UnsupportedLocaleException e) {
                err.println(e.getMessage());
                return ExitCodes.CANNOT_START;
            } catch (RuntimeException e) {
                return startFailed(e, line, err, locale, mask);
            }
            try {
                return runAll(names, loaded, engine, runInfo, locale);
            } catch (RuntimeException e) {
                // Scenarios have run: what was written so far stays, and the run counts as failed.
                LOG.error("The run stopped", e);
                err.println(Messages.render(Message.of("runner.run.stopped", mask.apply(describe(e))), locale));
                if (line.verbose()) {
                    err.print(mask.stackTrace(e));
                }
                return ExitCodes.FAILED;
            }
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

        private int runAll(List<String> names, Loaded loaded, ScenarioEngine engine, RunInfo runInfo,
                           Locale locale) {
            ConsoleProgress progress = new ConsoleProgress(out, locale);
            List<ScenarioResult> results = new ArrayList<>();
            int passed = 0;
            for (String name : names) {
                ScenarioResult result;
                Scenario scenario = loaded.scenarios().get(name);
                if (scenario == null) {
                    result = mask.result(loaded.failures().get(name));
                    Message detail = result.failureDetailMessage();
                    progress.failed(name, 0L,
                            mask.apply(detail != null ? Messages.render(detail, locale) : result.failureDetail()));
                } else {
                    result = mask.result(runOne(engine, scenario, name));
                    if (result.success()) {
                        progress.passed(name, result.durationMillis());
                    } else {
                        progress.failed(name, result.durationMillis(), mask.apply(result.failureSummary(locale)));
                    }
                }
                LOG.info("{} {}", result.success() ? "passed" : "failed", name);
                if (result.success()) {
                    passed++;
                }
                // Each result is masked like the console lines: the reports are the files people share.
                results.add(result);
                RunRecorder.write(results, folder.resolve(RESULTS_FILE), folder.resolve(REPORT_FILE), runInfo);
            }
            String report = workspace.root().relativize(folder.resolve(REPORT_FILE)).toString().replace('\\', '/');
            progress.summary(names.size(), passed, names.size() - passed, report);
            return passed == names.size() ? ExitCodes.OK : ExitCodes.FAILED;
        }

        /** The engine's result; an unexpected exception becomes a failed result, and the run goes on. */
        private ScenarioResult runOne(ScenarioEngine engine, Scenario scenario, String name) {
            long start = System.nanoTime();
            try {
                return engine.run(scenario, name);
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

        /** The report header, its filter being {@code --scenario} rather than the JUnit runners' property. */
        private RunInfo runInfo(Environment environment, Loaded loaded) {
            RunInfo base = RunInfos.of("runner.name.workspace", environment, List.copyOf(loaded.scenarios().values()));
            return new RunInfo(base.runner(), base.startedAt(), base.profiles(), line.scenario(), base.endpoints(),
                    base.locale());
        }
    }

    private record Loaded(Map<String, Scenario> scenarios, Map<String, ScenarioResult> failures) {
    }
}
