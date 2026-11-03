package com.idm.test.engine.spi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("File evidence masking and size limits")
class FileEvidenceTest {

    private static List<List<String>> rows(int count, String value) {
        List<List<String>> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(List.of("E" + i, value));
        }
        return rows;
    }

    @Test
    @DisplayName("Should mask secret-named columns and keep the others, in header order")
    void testMasksSecretColumns() {
        FileEvidence evidence = FileEvidence.written("hr-in", "hr.csv", 42, List.of("employeeId", "Password", "mail"),
                List.of(List.of("E1", "s3cret!", "a@b.c")));

        assertThat(evidence.rows()).singleElement().satisfies(row -> {
            assertThat(row.number()).isEqualTo(1);
            assertThat(row.selected()).isFalse();
            assertThat(row.values()).containsExactly(
                    org.assertj.core.api.Assertions.entry("employeeId", "E1"),
                    org.assertj.core.api.Assertions.entry("Password", "***"),
                    org.assertj.core.api.Assertions.entry("mail", "a@b.c"));
        });
        assertThat(evidence.toString()).doesNotContain("s3cret!");
        assertThat(evidence.operation()).isEqualTo("write");
        assertThat(evidence.present()).isTrue();
        assertThat(evidence.size()).isEqualTo(42L);
        assertThat(evidence.totalRows()).isEqualTo(1);
        assertThat(evidence.selectedRows()).isNull();
    }

    @Test
    @DisplayName("Should keep at most 50 rows and cut long values")
    void testRowAndValueLimits() {
        FileEvidence evidence = FileEvidence.written("f", "big.csv", 1, List.of("id", "note"),
                rows(80, "x".repeat(5_000)));

        assertThat(evidence.rows()).hasSizeLessThanOrEqualTo(FileEvidence.MAX_FILE_ROWS);
        assertThat(evidence.totalRows()).isEqualTo(80);
        assertThat(String.valueOf(evidence.rows().get(0).values().get("note")))
                .hasSizeLessThan(Evidence.MAX_VALUE_CHARS + 100).contains("truncated");
    }

    @Test
    @DisplayName("Should stop adding rows at the character budget")
    void testCharacterBudget() {
        // Each value is cut to about 1 KiB, so 50 rows of 1 KiB fit, but not with two wide columns.
        List<List<String>> wide = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            wide.add(List.of("x".repeat(2_000), "y".repeat(2_000)));
        }
        FileEvidence evidence = FileEvidence.written("f", "wide.csv", 1, List.of("a", "b"), wide);

        int chars = evidence.rows().stream()
                .flatMap(row -> row.values().values().stream()).mapToInt(v -> String.valueOf(v).length()).sum();
        assertThat(chars).isLessThanOrEqualTo(FileEvidence.MAX_FILE_CHARS);
        assertThat(evidence.rows().size()).isLessThan(50);
    }

    @Test
    @DisplayName("Should always keep the selected rows, even beyond the row limit")
    void testSelectedRowsKept() {
        FileEvidence evidence = FileEvidence.read("hr-out", "out.csv", 10, List.of("id", "status"),
                rows(200, "ok"), Set.of(150, 199));

        assertThat(evidence.rows()).filteredOn(FileEvidence.Row::selected)
                .extracting(FileEvidence.Row::number).containsExactly(151, 200);
        assertThat(evidence.rows()).hasSize(FileEvidence.MAX_FILE_ROWS + 2);
        assertThat(evidence.totalRows()).isEqualTo(200);
        assertThat(evidence.selectedRows()).isEqualTo(2);
        assertThat(evidence.rows()).extracting(FileEvidence.Row::number).isSorted();
    }

    @Test
    @DisplayName("Should give the same evidence when built again from its own parts")
    void testIdempotent() {
        FileEvidence first = FileEvidence.read("hr-out", "out.csv", 10, List.of("id", "password", "note"),
                rows3(120), Set.of(5, 110));

        FileEvidence again = new FileEvidence(first.folder(), first.file(), first.operation(), first.present(),
                first.size(), first.header(), first.rows(), first.totalRows(), first.selectedRows());

        assertThat(again).isEqualTo(first);
    }

    private static List<List<String>> rows3(int count) {
        List<List<String>> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(List.of("E" + i, "pw" + i, "n".repeat(i * 40)));
        }
        return rows;
    }

    @Test
    @DisplayName("Should describe a missing file, a delete and a failure without rows")
    void testFactories() {
        assertThat(FileEvidence.notFound("hr-out", "out-*.csv")).satisfies(e -> {
            assertThat(e.operation()).isEqualTo("read");
            assertThat(e.present()).isFalse();
            assertThat(e.file()).isEqualTo("out-*.csv");
            assertThat(e.rows()).isEmpty();
        });
        assertThat(FileEvidence.deleted("hr-in", "hr.csv", true).present()).isTrue();
        assertThat(FileEvidence.deleted("hr-in", "hr.csv", false).present()).isFalse();
        assertThat(FileEvidence.failed("hr-in", "hr.csv", "write")).satisfies(e -> {
            assertThat(e.present()).isNull();
            assertThat(e.header()).isEmpty();
            assertThat(e.totalRows()).isNull();
        });
    }
}
