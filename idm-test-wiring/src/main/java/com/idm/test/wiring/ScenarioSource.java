package com.idm.test.wiring;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/** Where scenario files come from: the classpath (JUnit runners) or a folder (a workspace). */
public interface ScenarioSource {

    /** Relative names with '/' separators, sorted. */
    List<String> names() throws IOException;

    InputStream open(String name) throws IOException;

    /** The location as shown in messages. */
    String describe();
}
