package com.idm.test.engine.spi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Command evidence masking and size limits")
class CommandEvidenceTest {

    @Test
    @DisplayName("Should mask the argument after a secret-looking option, and the value of --option=value")
    void testMasksSecretOptions() {
        CommandEvidence evidence = CommandEvidence.ran("driver-state",
                List.of("dxcmd", "-user", "admin", "-password", "fake-pw-1", "--token=fake-tok-2", "/ApiKey:fake-key-3",
                        "-getstate", "cn=HR"),
                Set.of(), 0, 12, false, "2", "", 2, 0);

        assertThat(evidence.argv()).containsExactly("dxcmd", "-user", "admin", "-password", "***", "--token=***",
                "/ApiKey:***", "-getstate", "cn=HR");
        assertThat(evidence.toString()).doesNotContain("fake-pw-1", "fake-tok-2", "fake-key-3");
    }

    @Test
    @DisplayName("Should mask an argument filled from a secret-named parameter")
    void testMasksSecretParameters() {
        CommandEvidence evidence = CommandEvidence.ran("set-pw", List.of("tool", "--for", "jdoe", "fake-new-pw"),
                Set.of(3), 0, 5, false, "", "", 0, 0);

        assertThat(evidence.argv()).containsExactly("tool", "--for", "jdoe", "***");
    }

    @Test
    @DisplayName("Should keep the exit code, duration, time-out and byte counts, and say when output was cut")
    void testResultFields() {
        String big = "x".repeat(Evidence.MAX_BODY_CHARS + 500);
        CommandEvidence evidence = CommandEvidence.ran("flood", List.of("tool"), Set.of(), -1, 30_000, true,
                big, "boom", 70_000, 4);

        assertThat(evidence.exitCode()).isEqualTo(-1);
        assertThat(evidence.timedOut()).isTrue();
        assertThat(evidence.durationMillis()).isEqualTo(30_000);
        assertThat(evidence.stdout()).hasSizeLessThan(big.length()).contains("truncated");
        assertThat(evidence.stdoutBytes()).isEqualTo(70_000);
        assertThat(evidence.stderr()).isEqualTo("boom");
    }

    @Test
    @DisplayName("Should give the same evidence when built again from its own parts")
    void testIdempotent() {
        CommandEvidence first = CommandEvidence.ran("c", List.of("t", "-password", "p", "x"), Set.of(3), 0, 1, false,
                "y".repeat(Evidence.MAX_BODY_CHARS + 10), "", 1, 0);

        CommandEvidence again = new CommandEvidence(first.command(), first.argv(), first.exitCode(),
                first.durationMillis(), first.timedOut(), first.stdout(), first.stderr(), first.stdoutBytes(),
                first.stderrBytes());

        assertThat(again).isEqualTo(first);
    }

    @Test
    @DisplayName("Should describe a command that never started with its name only")
    void testNotStarted() {
        CommandEvidence evidence = CommandEvidence.notStarted("ghost");

        assertThat(evidence.command()).isEqualTo("ghost");
        assertThat(evidence.argv()).isEmpty();
        assertThat(evidence.exitCode()).isNull();
        assertThat(evidence.stdout()).isNull();
    }
}
