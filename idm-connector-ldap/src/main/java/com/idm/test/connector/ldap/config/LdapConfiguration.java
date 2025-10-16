package com.idm.test.connector.ldap.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.LdapContextSource;
import org.springframework.ldap.pool.factory.PoolingContextSource;
import org.springframework.ldap.pool.validation.DefaultDirContextValidator;

/**
 * Configures Spring LDAP template with connection pooling.
 */
@Configuration
@EnableConfigurationProperties(LdapProperties.class)
public class LdapConfiguration {

    private static final Logger log = LoggerFactory.getLogger(LdapConfiguration.class);

    /**
     * Creates LdapContextSource from configuration properties.
     *
     * @param properties LDAP configuration properties
     * @return configured LdapContextSource
     */
    @Bean
    public LdapContextSource ldapContextSource(LdapProperties properties) {
        log.info("Configuring LDAP connection: {}", properties.getUrl());

        LdapContextSource contextSource = new LdapContextSource();
        contextSource.setUrl(properties.getUrl());
        contextSource.setBase(properties.getBaseDn());
        contextSource.setUserDn(properties.getUserDn());
        contextSource.setPassword(properties.getPassword());

        // Additional settings for stability
        contextSource.setPooled(false); // Disable built-in pooling, we'll use PoolingContextSource
        contextSource.setReferral("follow");

        try {
            contextSource.afterPropertiesSet();
            log.info("LDAP context source configured successfully");
        } catch (Exception e) {
            log.error("Failed to configure LDAP context source: {}", e.getMessage());
            throw new RuntimeException("Failed to configure LDAP", e);
        }

        return contextSource;
    }

    /**
     * Creates pooling context source wrapper around LdapContextSource.
     *
     * @param ldapContextSource the LDAP context source
     * @param properties LDAP configuration properties
     * @return pooled context source
     */
    @Bean
    public PoolingContextSource poolingContextSource(
            LdapContextSource ldapContextSource,
            LdapProperties properties) {

        if (!properties.isPooled()) {
            log.info("LDAP connection pooling is disabled");
            return null;
        }

        log.info("Configuring LDAP connection pool (min={}, max={})",
                properties.getMinIdle(), properties.getMaxActive());

        PoolingContextSource poolingContextSource = new PoolingContextSource();
        poolingContextSource.setContextSource(ldapContextSource);
        poolingContextSource.setDirContextValidator(new DefaultDirContextValidator());
        poolingContextSource.setTestOnBorrow(true);
        poolingContextSource.setTestWhileIdle(true);
        poolingContextSource.setMinIdle(properties.getMinIdle());
        poolingContextSource.setMaxActive(properties.getMaxActive());
        poolingContextSource.setMaxTotal(properties.getMaxTotal());
        poolingContextSource.setMaxWait(properties.getMaxWait());

        return poolingContextSource;
    }

    /**
     * Creates LdapTemplate bean.
     * Uses pooled context source if available, otherwise uses direct context source.
     *
     * @param ldapContextSource direct LDAP context source
     * @param poolingContextSource pooled context source (may be null)
     * @return configured LdapTemplate
     */
    @Bean
    public LdapTemplate ldapTemplate(
            LdapContextSource ldapContextSource,
            PoolingContextSource poolingContextSource) {

        LdapTemplate ldapTemplate;

        if (poolingContextSource != null) {
            log.info("Creating LdapTemplate with connection pooling");
            ldapTemplate = new LdapTemplate(poolingContextSource);
        } else {
            log.info("Creating LdapTemplate without connection pooling");
            ldapTemplate = new LdapTemplate(ldapContextSource);
        }

        ldapTemplate.setIgnorePartialResultException(true);

        log.info("LdapTemplate configured successfully");
        return ldapTemplate;
    }
}
