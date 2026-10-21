package com.idm.test.runner.ui;

import com.idm.test.runner.ui.api.WorkspaceGuard;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.servlet.autoconfigure.HttpEncodingAutoConfiguration;
import org.springframework.boot.servlet.autoconfigure.MultipartAutoConfiguration;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The Spring application {@code idmtest ui} starts: an embedded Tomcat, Spring MVC with JSON, the
 * static workbench from {@code classpath:/static/}, multipart uploads (Add files), and the controllers of this package and its
 * {@code api} package, all behind {@link TokenFilter} (and the workspace-scoped ones behind
 * {@link WorkspaceGuard}). Auto-configuration is a fixed list rather
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
        MultipartAutoConfiguration.class,
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

    /** The files, lab, runs and reports endpoints act only for the tab that shows the session's workspace. */
    @Bean
    WebMvcConfigurer workspaceGuard(UiSession session) {
        WorkspaceGuard guard = new WorkspaceGuard(session);
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(guard).addPathPatterns(WorkspaceGuard.PATHS);
            }
        };
    }

    /** {@code <user.home>/.idmtest/recent.json}, with {@code user.home} as it is when the workbench starts. */
    @Bean
    RecentWorkspaces recentWorkspaces() {
        return RecentWorkspaces.inUserHome();
    }
}
