package com.idm.test.engine.steps;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * What the exec step tests run with the current JVM, so they behave the same on every operating system.
 * Instructions, in order: {@code say TEXT} (stdout), {@code shout TEXT} (stderr), {@code args …} (each
 * remaining argument as {@code <arg>}), {@code file PATH} (prints the file), {@code touch PATH} (creates
 * it), {@code sleep MS},
 * {@code exit CODE}. Writes UTF-8.
 */
public final class ExecTestProgram {

    private ExecTestProgram() {
    }

    public static void main(String[] args) throws Exception {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "say" -> System.out.println(args[++i]);
                case "shout" -> System.err.println(args[++i]);
                case "args" -> {
                    for (int j = i + 1; j < args.length; j++) {
                        System.out.println("<" + args[j] + ">");
                    }
                    return;
                }
                case "file" -> {
                    Path path = Path.of(args[++i]);
                    System.out.print(Files.exists(path) ? Files.readString(path) : "");
                }
                case "touch" -> Files.writeString(Path.of(args[++i]), "ran");
                case "sleep" -> Thread.sleep(Long.parseLong(args[++i]));
                case "exit" -> {
                    System.out.flush();
                    System.exit(Integer.parseInt(args[++i]));
                }
                default -> throw new IllegalArgumentException(args[i]);
            }
        }
        System.out.flush();
    }
}
