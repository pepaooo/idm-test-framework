package com.idm.test.runner.ui;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.servlet.autoconfigure.HttpEncodingAutoConfiguration;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.core.Ordered;

/**
 * The Spring application {@code idmtest ui} starts: an embedded Tomcat, Spring MVC with JSON, the
 * static workbench from {@code classpath:/static/}, and the controllers of this package and its
 * {@code api} package, all behind {@link TokenFilter}. Auto-configuration is a fixed list rather
 * than everything on the class path: Spring Security is there (for the REST connector's OAuth2) and
 * must not add its own login, and the connectors are not needed to serve the workbench (runs start their own context).
 * {@link com.idm.test.runner.RunnerApplication} leaves this package out.
 */
@SpringBootConfiguration
@ImportAutoConfiguration({
        PropertyPlaceholderAutoConfiguration.class,
        TomcatServletWebServerAutoConfiguration.class,
        DispatcherServletAutoConfiguration.class,
        WebMvcAutoConfiguration.class,
        HttpEncodingAutoConfiguration.class,
        HttpMessageConvertersAutoConfiguration.class,
        JacksonAutoConfiguration.class,
        ErrorMvcAutoConfiguration.class})
@ComponentScan(basePackageClasses = UiServerConfig.class)
public class UiServerConfig {

    /** Before anything else, on every path: host, origin and token checks. */
    @Bean
    FilterRegistrationBean<TokenFilter> tokenFilter(UiSession session) {
        FilterRegistrationBean<TokenFilter> registration = new FilterRegistrationBean<>(new TokenFilter(session.token()));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        return registration;
    }

    /** {@code <user.home>/.idmtest/recent.json}, with {@code user.home} as it is when the workbench starts. */
    @Bean
    RecentWorkspaces recentWorkspaces() {
        return RecentWorkspaces.inUserHome();
    }

    @Bean
    GitTool gitTool() {
        return new GitTool();
    }
}
