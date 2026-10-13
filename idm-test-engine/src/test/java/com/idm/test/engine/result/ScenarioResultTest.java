package com.idm.test.engine.result;

import com.idm.test.engine.spi.Message;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Scenario result")
class ScenarioResultTest {

    private static final Locale ES = Locale.of("es");

    /** A step with a description that gave up after retrying. */
    private static ScenarioResult gaveUp() {
        return new ScenarioResult("n", "a.yaml", false, 1,
                List.of(new StepResult(0, "ldap.create", Map.of(), true, 1, "created", null, null, "Create"),
                        new StepResult(2, "ldap.verify", Map.of(), false, 1, "sn was 'X', expected 'Y'", 3, 10_060L,
                                "Wait until the entry exists")),
                List.of(), null, null);
    }

    /** A failed step without a description, and FAILED cleanup with and without one. */
    private static ScenarioResult stepAndCleanups() {
        return new ScenarioResult("n", "b.yaml", false, 1,
                List.of(new StepResult(0, "roles.grant", Map.of(), false, 1, "Role service error", null, null)),
                List.of(new CleanupResult(0, "ldap.delete", Map.of(), CleanupStatus.FAILED, 1, "no such entry",
                                "Remove the test account"),
                        new CleanupResult(1, "db.execute", Map.of(), CleanupStatus.FAILED, 1, "relation missing"),
                        new CleanupResult(2, "db.execute", Map.of(), CleanupStatus.SKIPPED, 0,
                                "skipped: [row] never bound")),
                null);
    }

    private static ScenarioResult cleanupOnly() {
        return new ScenarioResult("n", "c.yaml", false, 1,
                List.of(new StepResult(0, "ldap.create", Map.of(), true, 1, "created", null, null)),
                List.of(new CleanupResult(0, "ldap.delete", Map.of(), CleanupStatus.FAILED, 1, "no such entry", "Remove")),
                null);
    }

    private static ScenarioResult detail() {
        return new ScenarioResult("n", "d.yaml", false, 0, List.of(), "fixture 'user' failed: boom");
    }

    private static ScenarioResult none() {
        return new ScenarioResult("n", "e.yaml", false, 0, List.of(), null);
    }

    @Test
    @DisplayName("The English failure summary is exactly as before")
    void testEnglishSummaryUnchanged() {
        assertThat(gaveUp().failureSummary()).isEqualTo(
                "a.yaml, step 3 (ldap.verify: \"Wait until the entry exists\"): sn was 'X', expected 'Y'"
                        + " — gave up after 3 attempts over 10060ms");
        assertThat(stepAndCleanups().failureSummary()).isEqualTo(
                "b.yaml, step 1 (roles.grant): Role service error; "
                        + "b.yaml, cleanup entry 1 (ldap.delete: \"Remove the test account\"): no such entry; "
                        + "b.yaml, cleanup entry 2 (db.execute): relation missing");
        assertThat(cleanupOnly().failureSummary()).isEqualTo(
                "c.yaml, cleanup entry 1 (ldap.delete: \"Remove\"): no such entry");
        assertThat(detail().failureSummary()).isEqualTo("d.yaml: fixture 'user' failed: boom");
        assertThat(none().failureSummary()).isEqualTo("e.yaml: scenario failed before any step ran");
        assertThat(gaveUp().failureSummary(Locale.ENGLISH)).isEqualTo(gaveUp().failureSummary());
    }

    @Test
    @DisplayName("A successful scenario has no summary in any language")
    void testSuccessHasNoSummary() {
        ScenarioResult passed = new ScenarioResult("n", "ok.yaml", true, 1, List.of(), null);

        assertThat(passed.failureSummary()).isNull();
        assertThat(passed.failureSummary(ES)).isNull();
    }

    @Test
    @DisplayName("In Spanish the frame is translated and unkeyed messages stay as written")
    void testSpanishSummary() {
        assertThat(gaveUp().failureSummary(ES)).isEqualTo(
                "a.yaml, paso 3 (ldap.verify: \"Wait until the entry exists\"): sn was 'X', expected 'Y'"
                        + " — se dejó de reintentar tras 3 intentos en 10060 ms");
        assertThat(stepAndCleanups().failureSummary(ES)).isEqualTo(
                "b.yaml, paso 1 (roles.grant): Role service error; "
                        + "b.yaml, entrada de limpieza 1 (ldap.delete: \"Remove the test account\"): no such entry; "
                        + "b.yaml, entrada de limpieza 2 (db.execute): relation missing");
        assertThat(detail().failureSummary(ES)).isEqualTo("d.yaml: fixture 'user' failed: boom");
        assertThat(none().failureSummary(ES)).isEqualTo("e.yaml: el escenario falló antes de ejecutar ningún paso");
    }

    @Test
    @DisplayName("In Spanish a keyed step message is translated too")
    void testSpanishKeyedStepMessage() {
        StepResult keyed = new StepResult(0, "ldap.verify", Map.of(), false, 1,
                "x (last observed value after retrying)", null, null, null, null,
                "engine.wait.lastObserved", List.of("x"));
        CleanupResult keyedCleanup = new CleanupResult(0, "ldap.delete", Map.of(), CleanupStatus.FAILED, 1,
                "y (last observed value after retrying)", null, "engine.wait.lastObserved", List.of("y"));
        ScenarioResult result = new ScenarioResult("n", "k.yaml", false, 1, List.of(keyed), List.of(keyedCleanup), null);

        assertThat(result.failureSummary()).isEqualTo("k.yaml, step 1 (ldap.verify): x (last observed value after retrying)"
                + "; k.yaml, cleanup entry 1 (ldap.delete): y (last observed value after retrying)");
        assertThat(result.failureSummary(ES))
                .isEqualTo("k.yaml, paso 1 (ldap.verify): x (último valor observado tras reintentar)"
                        + "; k.yaml, entrada de limpieza 1 (ldap.delete): y (último valor observado tras reintentar)");
    }

    @Test
    @DisplayName("A keyed failure detail is translated, and the accessors give back the Message")
    void testKeyedFailureDetail() {
        ScenarioResult result = new ScenarioResult("n", "f.yaml", false, 0, List.of(), List.of(),
                "x (last observed value after retrying)", null, "engine.wait.lastObserved", List.of("x"));

        assertThat(result.failureSummary()).isEqualTo("f.yaml: x (last observed value after retrying)");
        assertThat(result.failureSummary(ES)).isEqualTo("f.yaml: x (último valor observado tras reintentar)");
        assertThat(result.failureDetailMessage()).isEqualTo(Message.of("engine.wait.lastObserved", "x"));
        assertThat(detail().failureDetailMessage()).isNull();
    }

    @Test
    @DisplayName("Message arguments may hold null, and an absent key gives no Message")
    void testMessageArgs() {
        java.util.ArrayList<Object> args = new java.util.ArrayList<>();
        args.add(null);
        StepResult step = new StepResult(0, "a", Map.of(), false, 1, "null (last observed value after retrying)",
                null, null, null, null, "engine.wait.lastObserved", args);
        CleanupResult entry = new CleanupResult(0, "a", Map.of(), CleanupStatus.FAILED, 1, "m", null, null, null);

        assertThat(step.messageArgs()).containsExactly((Object) null);
        assertThat(step.messageDetail()).isEqualTo(new Message("engine.wait.lastObserved", args));
        assertThat(entry.messageArgs()).isNull();
        assertThat(entry.messageDetail()).isNull();
    }
}
