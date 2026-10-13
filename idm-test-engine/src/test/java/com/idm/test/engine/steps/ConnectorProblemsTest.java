package com.idm.test.engine.steps;

import com.idm.test.connector.database.client.DatabaseProblem;
import com.idm.test.connector.rest.client.RestProblem;
import com.idm.test.connector.soap.client.SoapProblem;
import com.idm.test.core.model.OperationResult;
import com.idm.test.engine.i18n.Messages;
import com.idm.test.engine.spi.Message;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("REST, SOAP and database connector problems by key")
class ConnectorProblemsTest {

    private static final Object[] VALUES = {"v0", "v1", "v2", "v3"};

    @Test
    @DisplayName("Every REST problem reads in English exactly as the connector words it, and differently in Spanish")
    void testRestProblems() {
        for (RestProblem.Kind kind : RestProblem.Kind.values()) {
            RestProblem problem = RestProblem.of(kind, VALUES);
            assertThat(Messages.english(ConnectorProblems.of(problem))).as(kind.name()).isEqualTo(problem.english());
            assertThat(Messages.render(ConnectorProblems.of(problem), Locale.of("es"))).as(kind.name())
                    .isNotEqualTo(problem.english());
        }
    }

    @Test
    @DisplayName("Every SOAP problem reads in English exactly as the connector words it, and differently in Spanish")
    void testSoapProblems() {
        for (SoapProblem.Kind kind : SoapProblem.Kind.values()) {
            SoapProblem problem = SoapProblem.of(kind, VALUES);
            assertThat(Messages.english(ConnectorProblems.of(problem))).as(kind.name()).isEqualTo(problem.english());
            assertThat(Messages.render(ConnectorProblems.of(problem), Locale.of("es"))).as(kind.name())
                    .isNotEqualTo(problem.english());
        }
    }

    @Test
    @DisplayName("A failure whose problem cannot be rebuilt word for word keeps the connector's own text")
    void testUnrebuiltFailureKeepsItsText() {
        OperationResult<Object> other = OperationResult.failure("something else went wrong");

        assertThat(ConnectorProblems.request("GET", "http://x/y", other)).isEqualTo("something else went wrong");
        assertThat(ConnectorProblems.soap(other, SoapProblem::assignRoles)).isEqualTo("something else went wrong");
        assertThat(ConnectorProblems.keyedOr(Message.of("step.rest.send.failed", "a"), "Request failed: b"))
                .isEqualTo("Request failed: b");
    }

    @Test
    @DisplayName("An unsupported method is rebuilt from the connector's result")
    void testUnsupportedMethodRebuilt() {
        OperationResult<Object> refused = OperationResult.failure("Unsupported HTTP method: FETCH");

        Object argument = ConnectorProblems.request("FETCH", "http://x/y", refused);

        assertThat(argument).isInstanceOf(Message.class);
        assertThat(Messages.render((Message) argument, Locale.of("es"))).isEqualTo("Método HTTP no admitido: FETCH");
    }

    @Test
    @DisplayName("Every database problem reads in English exactly as the connector words it, and differently in Spanish")
    void testDatabaseProblems() {
        for (DatabaseProblem.Kind kind : DatabaseProblem.Kind.values()) {
            DatabaseProblem problem = new DatabaseProblem(kind, List.of(VALUES));
            assertThat(Messages.english(ConnectorProblems.of(problem))).as(kind.name()).isEqualTo(problem.english());
            assertThat(Messages.render(ConnectorProblems.of(problem), Locale.of("es"))).as(kind.name())
                    .isNotEqualTo(problem.english());
        }
        OperationResult<Object> plain = OperationResult.failure("Query failed: no throwable");
        assertThat(ConnectorProblems.database(plain, e -> new DatabaseProblem(DatabaseProblem.Kind.QUERY_FAILED,
                List.of("x")))).isEqualTo("Query failed: no throwable");
    }
}
