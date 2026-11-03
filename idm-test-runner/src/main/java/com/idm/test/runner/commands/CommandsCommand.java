package com.idm.test.runner.commands;

import com.idm.test.connector.exec.client.CommandConnector;
import com.idm.test.connector.exec.client.CommandDefinition;
import com.idm.test.connector.exec.client.RegisteredCommand;
import com.idm.test.engine.i18n.Messages;
import com.idm.test.engine.i18n.ReportLocale;
import com.idm.test.engine.spi.Message;
import com.idm.test.runner.CommandLine;
import com.idm.test.runner.ExitCodes;
import com.idm.test.runner.RunnerApplication;
import com.idm.test.runner.RunnerSettings;
import com.idm.test.runner.SecretsEnv;
import com.idm.test.runner.SecretsInitializer;
import com.idm.test.runner.UsageException;
import com.idm.test.runner.Workspace;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * {@code idmtest commands list|approve|revoke --profile <p> [--yes] [name …]}: shows the commands a
 * profile registers, exactly as they will run (secret references by name, never their values), and
 * approves or revokes them on this machine (ADR 0008). {@code approve} asks y/N for each command that is
 * new or changed since its approval; {@code --yes} approves without asking, for a pipeline whose operator
 * has reviewed the definitions. Without names, every command of the profile.
 */
public final class CommandsCommand {

    private CommandsCommand() {
    }

    public static int run(CommandLine line, BufferedReader in, PrintStream out, PrintStream err, ApprovalStore store) {
        Locale locale = locale(line.locale());
        String action = line.action();
        if (action == null || !List.of("list", "approve", "revoke").contains(action)) {
            throw new UsageException(Message.of("runner.commands.badAction"));
        }
        if (line.yes() && !action.equals("approve")) {
            throw new UsageException(Message.of("runner.commands.yesOnlyApprove"));
        }
        if (line.profile() == null) {
            throw new UsageException(Message.of("runner.commands.needsProfile"));
        }
        Workspace workspace = Workspace.open(line.workspace());
        Workspace.requireProfile(workspace, line.profile());
        Map<String, RegisteredCommand> commands = load(workspace, line.profile());
        Path root = workspace.root();
        String profile = line.profile();

        if (action.equals("revoke")) {
            List<String> names = line.names().isEmpty()
                    ? new ArrayList<>(new TreeSet<>(store.approvals(root, profile).keySet())) : line.names();
            for (String name : names) {
                boolean removed = store.revoke(root, profile, name);
                out.println(text(Message.of(removed ? "runner.commands.revoked" : "runner.commands.notRevoked", name),
                        locale));
            }
            return ExitCodes.OK;
        }

        if (commands.isEmpty()) {
            out.println(text(Message.of("runner.commands.none", profile), locale));
            return ExitCodes.OK;
        }
        for (String name : line.names()) {
            if (!commands.containsKey(name)) {
                throw new UsageException(Message.of("runner.commands.unknown", name, profile,
                        new TreeSet<>(commands.keySet())));
            }
        }
        List<String> names = line.names().isEmpty() ? new ArrayList<>(new TreeSet<>(commands.keySet())) : line.names();
        Map<String, ApprovalStore.Approval> approvals = store.approvals(root, profile);
        out.println(text(Message.of("runner.commands.header", profile, root), locale));
        for (String name : names) {
            RegisteredCommand command = commands.get(name);
            ApprovalStore.Approval approval = approvals.get(name);
            String state = approval == null ? "runner.commands.state.new"
                    : approval.hash().equals(command.hash()) ? "runner.commands.state.approved"
                    : "runner.commands.state.changed";
            out.println();
            out.println(name + "  (" + text(Message.of(state), locale) + ")");
            describe(command.asWritten(), out, locale);
            if (!action.equals("approve") || state.equals("runner.commands.state.approved")) {
                continue;
            }
            boolean approve = line.yes() || ask(name, in, out, locale);
            if (approve) {
                store.approve(root, profile, name, command.hash());
                out.println(text(Message.of("runner.commands.approved", name), locale));
            } else {
                out.println(text(Message.of("runner.commands.skipped", name), locale));
            }
        }
        return ExitCodes.OK;
    }

    /** The profile's commands as a run binds them: the same settings, secrets and Spring configuration. */
    public static Map<String, RegisteredCommand> load(Workspace workspace, String profile) {
        Map<String, String> secrets = SecretsEnv.read(workspace.secretsFile());
        List<String> args = new ArrayList<>(List.of(RunnerSettings.commandLineArgs(profile, null)));
        args.add("--spring.main.lazy-initialization=true");
        args.add("--spring.main.banner-mode=off");
        args.add("--logging.level.root=OFF");
        args.add("--logging.file.name=");
        args.add("--logging.file.path=");
        args.add("--logging.config=");
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(RunnerApplication.class)
                .web(WebApplicationType.NONE)
                .lazyInitialization(true)
                .registerShutdownHook(false)
                .logStartupInfo(false)
                .properties(RunnerSettings.defaultProperties(workspace))
                .initializers(new SecretsInitializer(secrets))
                .run(args.toArray(String[]::new))) {
            CommandConnector connector = context.getBean(CommandConnector.class);
            Map<String, RegisteredCommand> commands = new LinkedHashMap<>();
            for (String name : connector.commandNames()) {
                commands.put(name, connector.command(name).orElseThrow());
            }
            return commands;
        }
    }

    /** The definition as written: {@code ${NAME}} shown as the name, environment by name only. */
    private static void describe(CommandDefinition definition, PrintStream out, Locale locale) {
        out.println(text(Message.of("runner.commands.executable", definition.executable()), locale));
        out.println(text(Message.of("runner.commands.args", quoted(definition.args())), locale));
        definition.params().forEach((name, pattern) ->
                out.println(text(Message.of("runner.commands.param", name, pattern), locale)));
        if (!definition.env().isEmpty()) {
            out.println(text(Message.of("runner.commands.env", new TreeSet<>(definition.env().keySet())), locale));
        }
        String workingDir = definition.workingDir() == null
                ? text(Message.of("runner.commands.workspaceDir"), locale) : definition.workingDir();
        out.println(text(Message.of("runner.commands.limits", definition.timeout().toSeconds() + " s",
                String.valueOf(definition.maxOutput()), definition.exitCodes(), workingDir), locale));
    }

    private static String quoted(List<String> args) {
        List<String> shown = new ArrayList<>();
        for (String arg : args) {
            shown.add(arg.isEmpty() || arg.chars().anyMatch(Character::isWhitespace) ? "\"" + arg + "\"" : arg);
        }
        return String.join(" ", shown);
    }

    private static boolean ask(String name, BufferedReader in, PrintStream out, Locale locale) {
        out.print(text(Message.of("runner.commands.ask", name), locale));
        out.flush();
        try {
            String answer = in.readLine();
            if (answer == null) {
                out.println();
                return false;
            }
            String a = answer.strip().toLowerCase(Locale.ROOT);
            return a.equals("y") || a.equals("yes") || a.equals("s") || a.equals("si") || a.equals("sí");
        } catch (IOException e) {
            return false;
        }
    }

    private static Locale locale(String tag) {
        if (tag == null) {
            return Locale.ENGLISH;
        }
        try {
            return ReportLocale.resolve(tag);
        } catch (ReportLocale.UnsupportedLocaleException e) {
            throw new UsageException(Message.of("engine.text", e.getMessage()));
        }
    }

    private static String text(Message message, Locale locale) {
        return Messages.render(message, locale);
    }
}
