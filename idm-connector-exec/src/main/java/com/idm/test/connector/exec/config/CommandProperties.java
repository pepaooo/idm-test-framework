package com.idm.test.connector.exec.config;

import com.idm.test.connector.exec.client.CommandDefinition;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The registered commands ({@code idm.test.commands.<name>}). Example:
 * <pre>
 * idm:
 *   test:
 *     commands:
 *       driver-state:
 *         executable: /opt/novell/eDirectory/bin/dxcmd
 *         args: ["-user", "admin.sa.system", "-password", "${IDM_DXCMD_PASSWORD}", "-getstate", "{driver}"]
 *         params:
 *           driver: "[A-Za-z0-9 ._=,-]+"
 *         timeout: 30s
 * </pre>
 * Nothing here is checked at start-up: a definition that cannot run fails the step that uses it, naming
 * the problem. Whether a definition may run at all is decided by its approval on this machine.
 */
@ConfigurationProperties(prefix = "idm.test")
public class CommandProperties {

    private Map<String, CommandConfig> commands = new LinkedHashMap<>();

    public Map<String, CommandConfig> getCommands() {
        return commands;
    }

    public void setCommands(Map<String, CommandConfig> commands) {
        this.commands = commands;
    }

    /** One command. Only {@code executable} is required. */
    public static class CommandConfig {
        private String label;
        private String executable;
        private List<String> args = new ArrayList<>();
        private Map<String, String> params = new LinkedHashMap<>();
        private Map<String, String> env = new LinkedHashMap<>();
        private String workingDir;
        private Duration timeout;
        private DataSize maxOutput;
        private List<Integer> exitCodes = new ArrayList<>();
        private String encoding;

        public CommandDefinition toDefinition() {
            return new CommandDefinition(executable, args, params, env, workingDir, timeout,
                    maxOutput == null ? null : maxOutput.toBytes(), exitCodes, encoding);
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public String getExecutable() {
            return executable;
        }

        public void setExecutable(String executable) {
            this.executable = executable;
        }

        public List<String> getArgs() {
            return args;
        }

        public void setArgs(List<String> args) {
            this.args = args;
        }

        public Map<String, String> getParams() {
            return params;
        }

        public void setParams(Map<String, String> params) {
            this.params = params;
        }

        public Map<String, String> getEnv() {
            return env;
        }

        public void setEnv(Map<String, String> env) {
            this.env = env;
        }

        public String getWorkingDir() {
            return workingDir;
        }

        public void setWorkingDir(String workingDir) {
            this.workingDir = workingDir;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public DataSize getMaxOutput() {
            return maxOutput;
        }

        public void setMaxOutput(DataSize maxOutput) {
            this.maxOutput = maxOutput;
        }

        public List<Integer> getExitCodes() {
            return exitCodes;
        }

        public void setExitCodes(List<Integer> exitCodes) {
            this.exitCodes = exitCodes;
        }

        public String getEncoding() {
            return encoding;
        }

        public void setEncoding(String encoding) {
            this.encoding = encoding;
        }
    }

    /**
     * The text fields of a command exactly as written, {@code ${NAME}} unresolved: bound with a binder
     * that resolves no placeholders, for the hash an approval holds.
     */
    public static class CommandText {
        private String executable;
        private List<String> args = new ArrayList<>();
        private Map<String, String> params = new LinkedHashMap<>();
        private Map<String, String> env = new LinkedHashMap<>();
        private String workingDir;

        public String getExecutable() {
            return executable;
        }

        public void setExecutable(String executable) {
            this.executable = executable;
        }

        public List<String> getArgs() {
            return args;
        }

        public void setArgs(List<String> args) {
            this.args = args;
        }

        public Map<String, String> getParams() {
            return params;
        }

        public void setParams(Map<String, String> params) {
            this.params = params;
        }

        public Map<String, String> getEnv() {
            return env;
        }

        public void setEnv(Map<String, String> env) {
            this.env = env;
        }

        public String getWorkingDir() {
            return workingDir;
        }

        public void setWorkingDir(String workingDir) {
            this.workingDir = workingDir;
        }
    }
}
