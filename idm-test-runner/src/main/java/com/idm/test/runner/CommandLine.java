package com.idm.test.runner;

import com.idm.test.engine.result.PdfReportWriter;
import com.idm.test.engine.spi.Message;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The parsed {@code idmtest} command line. {@code --help}/{@code -h} and {@code --version} become
 * the commands {@code help} and {@code version}; no arguments at all is {@code help}. {@code port}
 * is {@code ui}'s {@code --port}, null when not given. {@code workspace} is the current folder when
 * {@code --workspace} is not given; {@code workspaceGiven} tells the two apart. For {@code commands},
 * {@code action} is {@code list}, {@code approve} or {@code revoke}, {@code names} the commands named
 * after it, and {@code yes} its {@code --yes}. For {@code report}, {@code runFolder} is the run folder,
 * {@code pdf} the validated {@code --pdf} kind ({@code summary} or {@code detailed}) and {@code out} the
 * {@code --out} file, null when not given.
 */
public record CommandLine(String command, Path workspace, String profile, String scenario, String locale,
                          boolean verbose, Path initTarget, Integer port, boolean workspaceGiven,
                          String action, List<String> names, boolean yes,
                          Path runFolder, String pdf, Path out) {

    public CommandLine {
        names = names == null ? List.of() : List.copyOf(names);
    }

    /** A command line without the {@code report} parts. */
    public CommandLine(String command, Path workspace, String profile, String scenario, String locale,
                       boolean verbose, Path initTarget, Integer port, boolean workspaceGiven,
                       String action, List<String> names, boolean yes) {
        this(command, workspace, profile, scenario, locale, verbose, initTarget, port, workspaceGiven, action,
                names, yes, null, null, null);
    }

    /** A command line without the {@code commands} parts. */
    public CommandLine(String command, Path workspace, String profile, String scenario, String locale,
                       boolean verbose, Path initTarget, Integer port, boolean workspaceGiven) {
        this(command, workspace, profile, scenario, locale, verbose, initTarget, port, workspaceGiven, null,
                List.of(), false);
    }

    public static CommandLine parse(String... args) {
        if (args.length == 0) {
            return of("help", null, null, null, null, false, null, null, false);
        }
        String first = args[0];
        switch (first) {
            case "--help", "-h", "help" -> {
                return of("help", null, null, null, null, false, null, null, false);
            }
            case "--version" -> {
                return of("version", null, null, null, null, false, null, null, false);
            }
            case "init", "validate", "run", "ui", "commands", "trust", "report" -> {
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
        Integer port = null;
        String action = null;
        List<String> names = new ArrayList<>();
        boolean yes = false;
        Path runFolder = null;
        String pdf = null;
        Path out = null;
        Iterator<String> rest = java.util.Arrays.asList(args).subList(1, args.length).iterator();
        while (rest.hasNext()) {
            String arg = rest.next();
            switch (arg) {
                case "--workspace" -> workspace = Path.of(value(arg, rest)).toAbsolutePath().normalize();
                case "--help", "-h" -> {
                    return of("help", null, null, null, null, false, null, null, false);
                }
                case "--profile" -> profile = value(arg, rest);
                case "--scenario" -> scenario = value(arg, rest);
                case "--locale" -> locale = value(arg, rest);
                case "--verbose" -> verbose = true;
                case "--yes" -> {
                    if (!first.equals("commands")) {
                        throw new UsageException(Message.of("runner.cli.unknownOption", arg));
                    }
                    yes = true;
                }
                case "--pdf" -> {
                    if (!first.equals("report")) {
                        throw new UsageException(Message.of("runner.cli.unknownOption", arg));
                    }
                    pdf = value(arg, rest);
                    try {
                        PdfReportWriter.Kind.parse(pdf);
                    } catch (IllegalArgumentException e) {
                        throw new UsageException(Message.of("runner.report.badPdf", pdf));
                    }
                }
                case "--out" -> {
                    if (!first.equals("report")) {
                        throw new UsageException(Message.of("runner.cli.unknownOption", arg));
                    }
                    out = Path.of(value(arg, rest)).toAbsolutePath().normalize();
                }
                case "--port" -> {
                    if (!first.equals("ui")) {
                        throw new UsageException(Message.of("runner.cli.unknownOption", arg));
                    }
                    port = port(value(arg, rest));
                }
                default -> {
                    if (first.equals("init") && !arg.startsWith("-") && initTarget == null) {
                        initTarget = Path.of(arg);
                    } else if (first.equals("report") && !arg.startsWith("-") && runFolder == null) {
                        runFolder = Path.of(arg).toAbsolutePath().normalize();
                    } else if ((first.equals("commands") || first.equals("trust")) && !arg.startsWith("-")) {
                        if (action == null) {
                            action = arg;
                        } else {
                            names.add(arg);
                        }
                    } else {
                        throw new UsageException(Message.of("runner.cli.unknownOption", arg));
                    }
                }
            }
        }
        if (first.equals("trust")) {
            if (workspace != null || profile != null || scenario != null || port != null || verbose || yes) {
                throw new UsageException(Message.of("runner.trust.options"));
            }
            if (!((("import".equals(action) || "import-server".equals(action)) && names.size() == 1)
                    || ("refresh".equals(action) && names.isEmpty()))) {
                throw new UsageException(Message.of("runner.trust.badAction"));
            }
        }
        if (first.equals("report")) {
            if (workspace != null || profile != null || scenario != null) {
                throw new UsageException(Message.of("runner.report.options"));
            }
            if (runFolder == null) {
                throw new UsageException(Message.of("runner.report.folderRequired"));
            }
            if (pdf == null) {
                throw new UsageException(Message.of("runner.report.pdfRequired"));
            }
        }
        CommandLine line = of(first, workspace, profile, scenario, locale, verbose, initTarget, port, workspace != null);
        return new CommandLine(line.command(), line.workspace(), line.profile(), line.scenario(), line.locale(),
                line.verbose(), line.initTarget(), line.port(), line.workspaceGiven(), action, names, yes, runFolder, pdf, out);
    }

    /** {@code value} as a TCP port, 0 to 65535 (0: any free port) but not 80. */
    private static int port(String value) {
        try {
            int port = Integer.parseInt(value);
            if (port == 80) {
                // http://127.0.0.1:80/ becomes http://127.0.0.1/, a Host the token filter refuses
                throw new UsageException(Message.of("runner.cli.port80"));
            }
            if (port >= 0 && port <= 65535) {
                return port;
            }
        } catch (NumberFormatException e) {
            // reported below
        }
        throw new UsageException(Message.of("runner.cli.badPort", value));
    }

    private static String value(String option, Iterator<String> rest) {
        if (!rest.hasNext()) {
            throw new UsageException(Message.of("runner.cli.missingValue", option));
        }
        String value = rest.next();
        if (value.startsWith("--") || value.equals("-h")) {
            throw new UsageException(Message.of("runner.cli.missingValue", option));
        }
        return value;
    }

    private static CommandLine of(String command, Path workspace, String profile, String scenario,
                                  String locale, boolean verbose, Path initTarget, Integer port,
                                  boolean workspaceGiven) {
        return new CommandLine(command, workspace != null ? workspace : Path.of("").toAbsolutePath(),
                profile, scenario, locale, verbose, initTarget, port, workspaceGiven);
    }
}
