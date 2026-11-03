package com.idm.test.connector.exec.config;

import com.idm.test.connector.exec.client.CommandConnector;
import com.idm.test.connector.exec.client.CommandDefinition;
import com.idm.test.connector.exec.client.RegisteredCommand;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** The {@link CommandConnector} over {@code idm.test.commands}, starting commands in the workspace. */
@Configuration
@EnableConfigurationProperties(CommandProperties.class)
public class CommandConfiguration {

    /** The workspace folder, where a command without {@code working-dir} starts; set by the runner. */
    public static final String WORKSPACE_PROPERTY = "idm.test.workspace";

    @Bean
    public CommandConnector commandConnector(CommandProperties properties, Environment environment) {
        return new CommandConnector(registered(properties, environment),
                Path.of(environment.getProperty(WORKSPACE_PROPERTY, ".")).toAbsolutePath());
    }

    /**
     * Each command as it will run, with the hash of its definition as written. The text fields
     * (executable, arguments, parameters, environment, working directory) are read a second time without
     * resolving {@code ${…}}, so a secret counts by its name; the other fields are taken as bound.
     */
    public static Map<String, RegisteredCommand> registered(CommandProperties properties, Environment environment) {
        Map<String, CommandProperties.CommandText> written = new Binder(ConfigurationPropertySources.get(environment))
                .bind("idm.test.commands", Bindable.mapOf(String.class, CommandProperties.CommandText.class))
                .orElseGet(Map::of);
        Map<String, RegisteredCommand> registered = new LinkedHashMap<>();
        properties.getCommands().forEach((name, config) -> {
            CommandDefinition resolved = config.toDefinition();
            CommandProperties.CommandText text = written.get(name);
            CommandDefinition asWritten = text == null ? resolved : new CommandDefinition(text.getExecutable(),
                    text.getArgs(), text.getParams(), text.getEnv(), text.getWorkingDir(), resolved.timeout(),
                    resolved.maxOutput(), resolved.exitCodes(), resolved.encoding());
            registered.put(name, RegisteredCommand.of(resolved, asWritten));
        });
        return registered;
    }
}
