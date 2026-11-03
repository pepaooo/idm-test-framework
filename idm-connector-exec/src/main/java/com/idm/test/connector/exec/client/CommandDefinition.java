package com.idm.test.connector.exec.client;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One registered command ({@code idm.test.commands.<name>}). Either as the runner will start it (secret
 * references resolved) or as written in the settings ({@code ${NAME}} kept), which is what an approval's
 * hash is taken over ({@link DefinitionHash}).
 *
 * @param executable an absolute path, or a name resolved on {@code PATH} when the process starts; never
 *                   a parameter
 * @param args       the arguments in order; one that is exactly {@code {name}} takes the parameter
 *                   {@code name}, braces anywhere else are literal
 * @param params     each parameter a scenario may pass, with the pattern its value must match in full
 * @param env        variables added to the inherited environment
 * @param workingDir where the process starts; {@code null} or blank for the workspace folder
 * @param timeout    after this the process and its descendants are killed; {@code null} for 60 seconds
 * @param maxOutput  bytes kept of stdout and of stderr each; {@code null} for 64 KiB
 * @param exitCodes  what {@code exec.run} treats as success; {@code null} or empty for {@code [0]}
 * @param encoding   how the output is decoded; {@code null} or blank for the platform's
 */
public record CommandDefinition(String executable, List<String> args, Map<String, String> params,
                                Map<String, String> env, String workingDir, Duration timeout, Long maxOutput,
                                List<Integer> exitCodes, String encoding) {

    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    public static final long DEFAULT_MAX_OUTPUT = 64 * 1024;

    public CommandDefinition {
        args = args == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(args));
        params = params == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(params));
        env = env == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(env));
        workingDir = workingDir == null || workingDir.isBlank() ? null : workingDir;
        timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
        maxOutput = maxOutput == null ? DEFAULT_MAX_OUTPUT : maxOutput;
        exitCodes = exitCodes == null || exitCodes.isEmpty() ? List.of(0) : List.copyOf(exitCodes);
        encoding = encoding == null || encoding.isBlank() ? null : encoding;
    }

    /** @return whether {@code arg} is a whole placeholder, {@code {name}} */
    static boolean isPlaceholder(String arg) {
        return arg != null && arg.length() > 2 && arg.startsWith("{") && arg.endsWith("}")
                && arg.substring(1, arg.length() - 1).matches("[A-Za-z0-9_-]+");
    }

    static String placeholderName(String arg) {
        return arg.substring(1, arg.length() - 1);
    }
}
