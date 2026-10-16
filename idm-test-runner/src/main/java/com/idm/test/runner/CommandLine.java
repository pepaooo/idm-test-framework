package com.idm.test.runner;

import com.idm.test.engine.spi.Message;

import java.nio.file.Path;
import java.util.Iterator;

/**
 * The parsed {@code idmtest} command line. {@code --help}/{@code -h} and {@code --version} become
 * the commands {@code help} and {@code version}; no arguments at all is {@code help}.
 */
public record CommandLine(String command, Path workspace, String profile, String scenario, String locale,
                          boolean verbose, Path initTarget) {

    public static CommandLine parse(String... args) {
        if (args.length == 0) {
            return of("help", null, null, null, null, false, null);
        }
        String first = args[0];
        switch (first) {
            case "--help", "-h", "help" -> {
                return of("help", null, null, null, null, false, null);
            }
            case "--version" -> {
                return of("version", null, null, null, null, false, null);
            }
            case "init", "validate", "run" -> {
            }
            default -> throw new UsageException(first.startsWith("-")
                    ? Message.of("runner.cli.unknownOption", first)
                    : Message.of("runner.cli.unknownCommand", first));
        }
        Path workspace = null;
        String profile = null;
        String scenario = null;
        String locale = null;
        boolean verbose = false;
        Path initTarget = null;
        Iterator<String> rest = java.util.Arrays.asList(args).subList(1, args.length).iterator();
        while (rest.hasNext()) {
            String arg = rest.next();
            switch (arg) {
                case "--workspace" -> workspace = Path.of(value(arg, rest)).toAbsolutePath().normalize();
                case "--help", "-h" -> {
                    return of("help", null, null, null, null, false, null);
                }
                case "--profile" -> profile = value(arg, rest);
                case "--scenario" -> scenario = value(arg, rest);
                case "--locale" -> locale = value(arg, rest);
                case "--verbose" -> verbose = true;
                default -> {
                    if (first.equals("init") && !arg.startsWith("-") && initTarget == null) {
                        initTarget = Path.of(arg);
                    } else {
                        throw new UsageException(Message.of("runner.cli.unknownOption", arg));
                    }
                }
            }
        }
        return of(first, workspace, profile, scenario, locale, verbose, initTarget);
    }

    private static String value(String option, Iterator<String> rest) {
        if (!rest.hasNext()) {
            throw new UsageException(Message.of("runner.cli.missingValue", option));
        }
        String value = rest.next();
        if (value.startsWith("--")) {
            throw new UsageException(Message.of("runner.cli.missingValue", option));
        }
        return value;
    }

    private static CommandLine of(String command, Path workspace, String profile, String scenario,
                                  String locale, boolean verbose, Path initTarget) {
        return new CommandLine(command, workspace != null ? workspace : Path.of("").toAbsolutePath(),
                profile, scenario, locale, verbose, initTarget);
    }
}
