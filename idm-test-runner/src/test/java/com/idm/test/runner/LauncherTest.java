package com.idm.test.runner;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.condition.OS.LINUX;
import static org.junit.jupiter.api.condition.OS.MAC;

/**
 * The POSIX {@code bin/idmtest} launcher in a fake install whose path has spaces. Each fake Java home's
 * {@code bin/java} is a shell script that prints its own path and then its arguments, one per line, so
 * the test sees which Java was chosen and exactly what it was given.
 */
@EnabledOnOs({MAC, LINUX})
@DisplayName("idmtest launcher (POSIX)")
class LauncherTest {

    private static final Path LAUNCHER = Path.of("src/dist/bin/idmtest");

    @TempDir
    Path temp;

    private Path home;

    @BeforeEach
    void install() throws IOException {
        home = temp.resolve("My Install/idmtest");
        Path bin = Files.createDirectories(home.resolve("bin"));
        Path launcher = Files.copy(LAUNCHER, bin.resolve("idmtest"), StandardCopyOption.REPLACE_EXISTING);
        makeExecutable(launcher);
        Files.createDirectories(home.resolve("lib/drivers"));
        Files.write(home.resolve("lib/idm-test-runner-0.jar"), new byte[] {'P', 'K', 3, 4});
    }

    @Test
    @DisplayName("Should refuse a Java older than 25 named by IDM_TEST_JAVA_HOME")
    void testRejectsJava21() throws Exception {
        Path java21 = fakeJava(temp.resolve("Java Homes/jdk 21"), "21.0.2");

        Result result = launch(home.resolve("bin/idmtest"), java21.toString());

        assertThat(result.exit()).isEqualTo(2);
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr().strip()).isEqualTo(
                "IDM_TEST_JAVA_HOME points to Java 21 (" + java21 + "); idmtest needs Java 25 or newer");
    }

    @Test
    @DisplayName("Should start the runner jar with the install's drivers on IDM_TEST_JAVA_HOME's Java 25")
    void testUsesJavaHomeVariable() throws Exception {
        Path java25 = fakeJava(temp.resolve("Java Homes/jdk 25"), "25.0.3");

        Result result = launch(home.resolve("bin/idmtest"), java25.toString());

        assertThat(result.exit()).isZero();
        assertThat(result.stderr()).isEmpty();
        assertThat(result.stdout().lines().toList()).containsExactly(
                java25.resolve("bin/java").toString(),
                "-Dloader.path=" + home + "/lib/drivers",
                "-jar",
                home + "/lib/idm-test-runner-0.jar",
                "--version");
    }

    @Test
    @DisplayName("Should say a folder without a release file is not a Java installation")
    void testRejectsFolderWithoutRelease() throws Exception {
        Path notJava = Files.createDirectories(temp.resolve("Not A Java"));

        Result result = launch(home.resolve("bin/idmtest"), notJava.toString());

        assertThat(result.exit()).isEqualTo(2);
        assertThat(result.stderr().strip()).isEqualTo("IDM_TEST_JAVA_HOME points to " + notJava
                + ", which is not a Java installation; idmtest needs Java 25 or newer");
    }

    @Test
    @DisplayName("Should use the bundled runtime when IDM_TEST_JAVA_HOME is not set")
    void testUsesBundledRuntime() throws Exception {
        Path runtime = fakeJava(home.resolve("runtime"), "25.0.3");

        Result result = launch(home.resolve("bin/idmtest"), null);

        assertThat(result.exit()).isZero();
        assertThat(result.stdout().lines().findFirst()).contains(runtime.resolve("bin/java").toString());
    }

    @Test
    @DisplayName("Should call the bundled runtime missing, not IDM_TEST_JAVA_HOME, when the variable is not set")
    void testMissingBundledRuntime() throws Exception {
        Result result = launch(home.resolve("bin/idmtest"), null);

        assertThat(result.exit()).isEqualTo(2);
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr().strip()).isEqualTo("the bundled Java runtime in " + home.resolve("runtime")
                + " is missing or broken; reinstall idmtest or set IDM_TEST_JAVA_HOME to a Java 25+");
    }

    @Test
    @DisplayName("Should call a bundled runtime older than 25 broken when IDM_TEST_JAVA_HOME is not set")
    void testOldBundledRuntime() throws Exception {
        Path runtime = fakeJava(home.resolve("runtime"), "21.0.2");

        Result result = launch(home.resolve("bin/idmtest"), null);

        assertThat(result.exit()).isEqualTo(2);
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr().strip()).isEqualTo("the bundled Java runtime in " + runtime
                + " is missing or broken; reinstall idmtest or set IDM_TEST_JAVA_HOME to a Java 25+");
    }

    @Test
    @DisplayName("Should find its install folder when started through a symbolic link on PATH")
    void testStartsThroughSymlink() throws Exception {
        Path runtime = fakeJava(home.resolve("runtime"), "25.0.3");
        Path pathDir = Files.createDirectories(temp.resolve("Path Dir"));
        Path link = Files.createSymbolicLink(pathDir.resolve("idmtest"), home.resolve("bin/idmtest"));

        Result result = launch(link, null);

        assertThat(result.exit()).isZero();
        assertThat(result.stdout().lines().toList()).containsExactly(
                runtime.resolve("bin/java").toString(),
                "-Dloader.path=" + home + "/lib/drivers",
                "-jar",
                home + "/lib/idm-test-runner-0.jar",
                "--version");
    }

    @Test
    @DisplayName("Should accept a release that gives only the major version")
    void testAcceptsMajorOnlyVersion() throws Exception {
        Path java25 = fakeJava(temp.resolve("Java Homes/jdk 25"), "25");

        Result result = launch(home.resolve("bin/idmtest"), java25.toString());

        assertThat(result.exit()).isZero();
        assertThat(result.stdout().lines().findFirst()).contains(java25.resolve("bin/java").toString());
    }

    @Test
    @DisplayName("Should call the version unknown when the release file has no JAVA_VERSION line")
    void testReleaseWithoutVersion() throws Exception {
        Path javaHome = fakeJava(temp.resolve("Java Homes/odd jdk"), "25.0.3");
        Files.writeString(javaHome.resolve("release"), "IMPLEMENTOR=\"Test\"\n", StandardCharsets.UTF_8);

        Result result = launch(home.resolve("bin/idmtest"), javaHome.toString());

        assertThat(result.exit()).isEqualTo(2);
        assertThat(result.stderr().strip()).isEqualTo(
                "IDM_TEST_JAVA_HOME points to Java unknown (" + javaHome + "); idmtest needs Java 25 or newer");
    }

    @Test
    @DisplayName("Should say the install is broken when the runner jar is missing")
    void testMissingRunnerJar() throws Exception {
        fakeJava(home.resolve("runtime"), "25.0.3");
        Files.delete(home.resolve("lib/idm-test-runner-0.jar"));

        Result result = launch(home.resolve("bin/idmtest"), null);

        assertThat(result.exit()).isEqualTo(2);
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).isEqualTo("idmtest is not installed correctly: "
                + "lib/idm-test-runner-*.jar not found in " + home + "\n");
    }

    @Test
    @DisplayName("Should find its install folder when CDPATH is set and it is started by a relative path")
    void testIgnoresCdpath() throws Exception {
        Path runtime = fakeJava(home.resolve("runtime"), "25.0.3");
        // A decoy with the same relative layout: a cd that consults CDPATH would land (and print) here.
        Path decoy = Files.createDirectories(temp.resolve("Decoy/idmtest/bin")).getParent().getParent();

        Result result = launch(List.of("/bin/sh", "-c", "idmtest/bin/idmtest --version"),
                home.getParent(), null, Map.of("CDPATH", decoy.toString()));

        // The shell starts in the physical folder (macOS: /private/var/...), so pwd reports the real path.
        Path realHome = home.toRealPath();
        assertThat(result.exit()).isZero();
        assertThat(result.stderr()).isEmpty();
        assertThat(result.stdout().lines().toList()).containsExactly(
                runtime.toRealPath().resolve("bin/java").toString(),
                "-Dloader.path=" + realHome + "/lib/drivers",
                "-jar",
                realHome + "/lib/idm-test-runner-0.jar",
                "--version");
    }

    private static Path fakeJava(Path javaHome, String version) throws IOException {
        Path java = Files.createDirectories(javaHome.resolve("bin")).resolve("java");
        Files.writeString(java, "#!/bin/sh\nprintf '%s\\n' \"$0\" \"$@\"\n", StandardCharsets.UTF_8);
        makeExecutable(java);
        Files.writeString(javaHome.resolve("release"),
                "IMPLEMENTOR=\"Test\"\nJAVA_VERSION=\"" + version + "\"\n", StandardCharsets.UTF_8);
        return javaHome;
    }

    private static void makeExecutable(Path file) throws IOException {
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    private Result launch(Path launcher, String javaHome) throws Exception {
        return launch(List.of(launcher.toString(), "--version"), temp, javaHome, Map.of());
    }

    private Result launch(List<String> command, Path directory, String javaHome, Map<String, String> env)
            throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectOutput(temp.resolve("out.txt").toFile())
                .redirectError(temp.resolve("err.txt").toFile());
        builder.environment().remove("IDM_TEST_JAVA_HOME");
        builder.environment().remove("CDPATH");
        if (javaHome != null) {
            builder.environment().put("IDM_TEST_JAVA_HOME", javaHome);
        }
        builder.environment().putAll(env);
        Process process = builder.start();
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
        return new Result(process.exitValue(),
                Files.readString(temp.resolve("out.txt")),
                Files.readString(temp.resolve("err.txt")));
    }

    private record Result(int exit, String stdout, String stderr) {
    }
}
