package com.idm.test.connector.exec.client;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A small program the connector tests run with the current JVM, so they need no operating-system
 * tool and behave the same on Windows, Linux and macOS. Its arguments are instructions, run in order:
 * <ul>
 *   <li>{@code args a b …}: prints each remaining argument as {@code <arg>} on its own line, then stops</li>
 *   <li>{@code out N} / {@code err N}: writes N times {@code x} to stdout / stderr</li>
 *   <li>{@code say TEXT} / {@code shout TEXT}: prints TEXT and a line break to stdout / stderr</li>
 *   <li>{@code bytes CHARSET TEXT}: writes TEXT encoded in CHARSET to stdout</li>
 *   <li>{@code env NAME}: prints the variable's value, or {@code <unset>}</li>
 *   <li>{@code pwd}: prints the working directory</li>
 *   <li>{@code file PATH}: prints the file's content</li>
 *   <li>{@code sleep MS}: sleeps</li>
 *   <li>{@code child MS PIDFILE}: starts this program sleeping MS, writes its pid to PIDFILE</li>
 *   <li>{@code exit CODE}: exits with CODE</li>
 * </ul>
 */
public final class TestProgram {

    private TestProgram() {
    }

    public static void main(String[] args) throws Exception {
        OutputStream out = System.out;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "args" -> {
                    for (int j = i + 1; j < args.length; j++) {
                        System.out.println("<" + args[j] + ">");
                    }
                    System.out.flush();
                    return;
                }
                case "out" -> repeat(System.out, Integer.parseInt(args[++i]));
                case "err" -> repeat(System.err, Integer.parseInt(args[++i]));
                case "say" -> System.out.println(args[++i]);
                case "shout" -> System.err.println(args[++i]);
                case "bytes" -> {
                    Charset charset = Charset.forName(args[++i]);
                    out.write(args[++i].getBytes(charset));
                    out.flush();
                }
                case "env" -> {
                    String value = System.getenv(args[++i]);
                    System.out.println(value == null ? "<unset>" : value);
                }
                case "pwd" -> System.out.println(Path.of("").toAbsolutePath());
                case "file" -> System.out.print(Files.readString(Path.of(args[++i])));
                case "sleep" -> Thread.sleep(Long.parseLong(args[++i]));
                case "child" -> {
                    String millis = args[++i];
                    Path pidFile = Path.of(args[++i]);
                    List<String> command = new ArrayList<>(List.of(
                            ProcessHandle.current().info().command().orElseThrow(),
                            "-cp", System.getProperty("java.class.path"),
                            TestProgram.class.getName(), "sleep", millis));
                    Process child = new ProcessBuilder(command).inheritIO().start();
                    Files.writeString(pidFile, Long.toString(child.pid()));
                }
                case "exit" -> {
                    System.out.flush();
                    System.err.flush();
                    System.exit(Integer.parseInt(args[++i]));
                }
                default -> throw new IllegalArgumentException("unknown instruction " + args[i]);
            }
        }
        System.out.flush();
        System.err.flush();
    }

    private static void repeat(java.io.PrintStream stream, int count) throws IOException {
        byte[] chunk = new byte[8192];
        java.util.Arrays.fill(chunk, (byte) 'x');
        int left = count;
        while (left > 0) {
            int n = Math.min(left, chunk.length);
            stream.write(chunk, 0, n);
            left -= n;
        }
        stream.flush();
    }
}
