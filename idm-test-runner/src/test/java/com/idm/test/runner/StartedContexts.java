package com.idm.test.runner;

import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationListener;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test only, registered in {@code META-INF/spring.factories}: what each Spring application started
 * in this JVM was, so a test can check that a CLI command started no web server.
 */
public class StartedContexts implements ApplicationListener<ApplicationStartedEvent> {

    /** One started application: its main source, whether it is a web server, and the port it bound. */
    public record Started(String source, boolean webServer, String localServerPort) {
    }

    private static final List<Started> STARTED = new CopyOnWriteArrayList<>();

    @Override
    public void onApplicationEvent(ApplicationStartedEvent event) {
        STARTED.add(new Started(
                event.getSpringApplication().getMainApplicationClass() + " " + event.getSpringApplication().getAllSources(),
                event.getApplicationContext() instanceof WebServerApplicationContext,
                event.getApplicationContext().getEnvironment().getProperty("local.server.port")));
    }

    /** The applications started so far, oldest first. */
    public static List<Started> all() {
        return List.copyOf(STARTED);
    }
}
