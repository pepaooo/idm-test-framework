package com.idm.test.connector.file.config;

import com.idm.test.connector.file.client.DelimitedFileConnector;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The {@link DelimitedFileConnector} over the folders of {@code idm.test.files}. */
@Configuration
@EnableConfigurationProperties(FileProperties.class)
public class FileConfiguration {

    @Bean
    public DelimitedFileConnector delimitedFileConnector(FileProperties properties) {
        return new DelimitedFileConnector(properties.toSettings());
    }
}
