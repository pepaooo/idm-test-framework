package com.idm.test.engine.spi;

import com.idm.test.core.model.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Failure kind")
class FailureKindTest {

    @ParameterizedTest
    @EnumSource(value = ErrorType.class, names = {"NETWORK_ERROR", "TIMEOUT", "AUTHENTICATION_ERROR",
            "CONFIGURATION_ERROR"})
    @DisplayName("Unreachable, timed out, refused credentials and configuration are setup problems")
    void testSetupTypes(ErrorType type) {
        assertThat(FailureKind.of(type)).isEqualTo(FailureKind.SETUP);
    }

    @ParameterizedTest
    @EnumSource(value = ErrorType.class, mode = EnumSource.Mode.EXCLUDE, names = {"NETWORK_ERROR", "TIMEOUT",
            "AUTHENTICATION_ERROR", "CONFIGURATION_ERROR"})
    @DisplayName("Every other error type is a finding: the system answered")
    void testFindingTypes(ErrorType type) {
        assertThat(FailureKind.of(type)).isEqualTo(FailureKind.FINDING);
    }

    @Test
    @DisplayName("An outcome's kind: none on success, finding by default, setup when final or typed so")
    void testOutcomeKinds() {
        assertThat(StepOutcome.success("ok").kind()).isNull();
        assertThat(StepOutcome.success(Message.of("engine.set.bound", "x"), 1).kind()).isNull();
        assertThat(StepOutcome.failure("no").kind()).isEqualTo(FailureKind.FINDING);
        assertThat(StepOutcome.failure(Message.of("engine.wait.timedOut")).kind()).isEqualTo(FailureKind.FINDING);
        assertThat(new StepOutcome(false, "no", null).kind()).isEqualTo(FailureKind.FINDING);
        assertThat(StepOutcome.finalFailure("never").kind()).isEqualTo(FailureKind.SETUP);
        assertThat(StepOutcome.finalFailure(Message.of("engine.wait.timedOut")).kind()).isEqualTo(FailureKind.SETUP);
        assertThat(StepOutcome.failure("down", ErrorType.NETWORK_ERROR).kind()).isEqualTo(FailureKind.SETUP);
        assertThat(StepOutcome.failure("down", ErrorType.NETWORK_ERROR).isFinal()).isFalse();
        assertThat(StepOutcome.failure(Message.of("engine.wait.timedOut"), ErrorType.SERVER_ERROR).kind())
                .isEqualTo(FailureKind.FINDING);
        assertThat(StepOutcome.failure("down", ErrorType.TIMEOUT).withEvidence(null).kind())
                .isEqualTo(FailureKind.SETUP);
    }
}
