package com.idm.test.runner;

import com.idm.test.IdmTestFrameworkApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * The Spring application {@code idmtest run} starts: the connectors, the executor and the data
 * generator from every {@code com.idm.test} module. Like the JUnit runners, it leaves out Spring
 * Boot's own LDAP and DataSource auto-configuration (the connectors build their own), the framework's
 * {@link IdmTestFrameworkApplication}, and the runner's own package.
 */
@SpringBootConfiguration
@EnableAutoConfiguration(excludeName = {
        "org.springframework.boot.ldap.autoconfigure.LdapAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration"})
@ComponentScan(basePackages = "com.idm.test", excludeFilters = {
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class),
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = IdmTestFrameworkApplication.class),
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = "com\\.idm\\.test\\.runner\\..*")})
public class RunnerApplication {
}
