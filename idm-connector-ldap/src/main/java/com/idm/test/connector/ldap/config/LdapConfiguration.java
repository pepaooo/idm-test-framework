package com.idm.test.connector.ldap.config;

import com.idm.test.connector.ldap.client.EdirectoryConnector;
import com.idm.test.connector.ldap.client.LdapDirectories;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.LdapContextSource;
import org.springframework.ldap.pool2.factory.PoolConfig;
import org.springframework.ldap.pool2.factory.PooledContextSource;
import org.springframework.ldap.pool2.validation.DefaultDirContextValidator;

import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Builds one LdapTemplate and {@link EdirectoryConnector} per configured directory
 * ({@code idm.test.directories.<name>}, plus the older {@code idm.test.ldap} block as
 * {@code default}), held in {@link LdapDirectories}. The default directory's template and
 * connector are also beans, for the Java tier. Nothing connects here: a connection opens when
 * a directory is first used.
 */
@Configuration
@EnableConfigurationProperties({LdapProperties.class, LdapDirectoriesProperties.class})
public class LdapConfiguration {

    private static final Logger log = LoggerFactory.getLogger(LdapConfiguration.class);

    static final String REPLACED_WARNING = "idm.test.directories.default replaces the idm.test.ldap block";

    @Bean
    public LdapDirectories ldapDirectories(LdapProperties ldapProperties, LdapDirectoriesProperties directoriesProperties) {
        Map<String, LdapProperties> configs =
                directoryConfigs(ldapProperties, directoriesProperties.getDirectories(), log::warn);
        Map<String, LdapTemplate> templates = new TreeMap<>();
        configs.forEach((name, config) -> templates.put(name, buildTemplate(name, config)));
        LdapDirectories directories = LdapDirectories.of(templates, directoriesProperties.getDefaultDirectory());
        log.info("LDAP directories {} (default: {})", directories.names(), directories.defaultName().orElse("none"));
        return directories;
    }

    /** The default directory's template, for the Java tier; null (no bean) when there is no default. */
    @Bean
    public LdapTemplate ldapTemplate(LdapDirectories ldapDirectories) {
        return ldapDirectories.defaultName().flatMap(ldapDirectories::template).orElse(null);
    }

    /** The default directory's connector, for the Java tier; null (no bean) when there is no default. */
    @Bean
    public EdirectoryConnector edirectoryConnector(LdapDirectories ldapDirectories) {
        return ldapDirectories.defaultName().flatMap(ldapDirectories::connector).orElse(null);
    }

    /**
     * The named directories, plus the older {@code idm.test.ldap} block as {@code default} when
     * its url is set. A named {@code default} replaces the older block, with a warning.
     */
    static Map<String, LdapProperties> directoryConfigs(
            LdapProperties legacy, Map<String, LdapProperties> directories, Consumer<String> warn) {
        Map<String, LdapProperties> configs = new TreeMap<>(directories);
        if (legacy != null && legacy.getUrl() != null && !legacy.getUrl().isBlank()) {
            if (configs.containsKey(LdapDirectories.DEFAULT)) {
                warn.accept(REPLACED_WARNING);
            } else {
                configs.put(LdapDirectories.DEFAULT, legacy);
            }
        }
        return configs;
    }

    /** Context source, then an optional pool, then the template: the chain one directory always used. */
    static LdapTemplate buildTemplate(String name, LdapProperties config) {
        if (config.getUrl() == null || config.getUrl().isBlank()) {
            throw new IllegalStateException("idm.test.directories." + name + ".url is not set");
        }
        log.info("Configuring LDAP directory '{}': {}", name, config.getUrl());

        LdapContextSource contextSource = new LdapContextSource();
        contextSource.setUrl(config.getUrl());
        contextSource.setBase(config.getBaseDn());
        contextSource.setUserDn(config.getUserDn());
        contextSource.setPassword(config.getPassword());
        contextSource.setPooled(false); // PooledContextSource below does the pooling
        contextSource.setReferral("follow");
        try {
            contextSource.afterPropertiesSet();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to configure LDAP directory '" + name + "'", e);
        }

        LdapTemplate template;
        if (config.isPooled()) {
            PoolConfig poolConfig = new PoolConfig();
            poolConfig.setTestOnBorrow(true);
            poolConfig.setTestWhileIdle(true);
            poolConfig.setMinIdlePerKey(config.getMinIdle());
            poolConfig.setMaxTotalPerKey(config.getMaxActive()); // the old maxActive was per key too
            poolConfig.setMaxTotal(config.getMaxTotal());
            poolConfig.setMaxWaitMillis(config.getMaxWait()); // negative: wait indefinitely, as before
            // Pool 1 behaviour made explicit: callers block when the pool is exhausted, and no JMX beans.
            poolConfig.setBlockWhenExhausted(true);
            poolConfig.setJmxEnabled(false);
            PooledContextSource pool = new PooledContextSource(contextSource, poolConfig);
            pool.setDirContextValidator(new DefaultDirContextValidator());
            template = new LdapTemplate(pool);
        } else {
            template = new LdapTemplate(contextSource);
        }
        template.setIgnorePartialResultException(true);
        return template;
    }
}
