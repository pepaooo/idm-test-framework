# ITF-59 ConsoleProgress ASCII Fallback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fall back from `✔`/`✖` to `[OK]`/`[FAIL]` in `ConsoleProgress` when the output stream's charset cannot encode `✔` and `✖`, and update tests, Windows CI smoke checks, and the runner guide.

**Architecture:** `ConsoleProgress` inspects `out.charset().newEncoder().canEncode("✔✖")` once at construction and selects `"✔"` / `"✖"` or `"[OK]"` / `"[FAIL]"` as prefixes for `passed`, `failed`, `validated`, and `invalid`. The Windows CI smoke test in `.github/workflows/runner-dist.yml` and the runner documentation in `docs/guides/runner.md` are updated to reflect and verify the fallback.

**Tech Stack:** Java 25, JUnit Jupiter, AssertJ, GitHub Actions (`cmd` shell on Windows), Markdown.

**Spec:** `docs/superpowers/specs/2026-11-01-itf-59-console-progress-ascii-fallback-design.md`

## Global Constraints

- Check `out.charset().newEncoder().canEncode("✔✖")` in `ConsoleProgress`.
- When it can encode both, print `✔` and `✖`; when it cannot, print `[OK]` and `[FAIL]` (same column layout and rest of the line).
- No changes to the rest of the messages' text, summary lines, exit codes, reports, or `run.log`.
- Preserve unrelated working-tree changes in `idm-test-scenarios/`.

## Review Focus

- `passed` and `validated` on a non-Unicode stream (`US-ASCII` / `windows-1252`) must print `[OK] <name>  <duration>` and `[OK] <name>` without `?` replacement characters (Task 1).
- `failed` and `invalid` on a non-Unicode stream (`US-ASCII` / `windows-1252`) must print `[FAIL] <name>  <duration> — <message>` and `[FAIL] <name> — <message>` (Task 1).
- `UTF-8` streams must continue printing `✔` and `✖` across all four methods (`passed`, `failed`, `validated`, `invalid`) (Task 1).
- `summary` line must remain unchanged regardless of stream charset (Task 1).
- Windows CI `cmd` smoke step must fail if `idmtest validate` prints neither `[OK] example.yaml` nor `✔ example.yaml` (e.g. if it prints `? example.yaml`) (Task 1).

---

### Task 1: Implement `ConsoleProgress` charset fallback, unit tests, CI smoke check, and docs

**Files:**
- Modify: `idm-test-runner/src/main/java/com/idm/test/runner/ConsoleProgress.java`
- Modify: `idm-test-runner/src/test/java/com/idm/test/runner/ConsoleProgressTest.java`
- Modify: `.github/workflows/runner-dist.yml:73-83`
- Modify: `docs/guides/runner.md:191-194`

**Interfaces:**
- Consumes: `PrintStream.charset()`, `Charset.newEncoder()`, `CharsetEncoder.canEncode(CharSequence)`
- Produces: unchanged public signature `ConsoleProgress(PrintStream out, Locale locale)` with methods `passed(String, long)`, `failed(String, long, String, FailureKind)`, `validated(String)`, `invalid(String, String)`, `summary(int, int, int, String)`

- [x] **Step 1: Write failing tests in `ConsoleProgressTest.java`**

Add tests in `idm-test-runner/src/test/java/com/idm/test/runner/ConsoleProgressTest.java` covering `UTF-8`, `US-ASCII`, and `Charset.forName("windows-1252")` across `passed`, `failed`, `validated`, and `invalid`:

```java
    @Test
    @DisplayName("UTF-8 streams keep ✔ and ✖ for run and validate lines")
    void testUtf8KeepsSymbols() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        ConsoleProgress progress = new ConsoleProgress(out, Locale.ENGLISH);
        progress.passed("a.yaml", 12);
        progress.failed("b.yaml", 34, "boom", FailureKind.FINDING);
        progress.validated("c.yaml");
        progress.invalid("d.yaml", "bad step");
        assertThat(bytes.toString(StandardCharsets.UTF_8).lines().toList()).containsExactly(
                "✔ a.yaml  12 ms",
                "✖ b.yaml  34 ms — boom · finding",
                "✔ c.yaml",
                "✖ d.yaml — bad step");
    }

    @Test
    @DisplayName("Streams whose charset cannot encode ✔ and ✖ fall back to [OK] and [FAIL]")
    void testFallbackMarksWhenCharsetCannotEncodeSymbols() {
        for (Charset charset : List.of(StandardCharsets.US_ASCII, Charset.forName("windows-1252"))) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            PrintStream out = new PrintStream(bytes, true, charset);
            ConsoleProgress progress = new ConsoleProgress(out, Locale.ENGLISH);
            progress.passed("a.yaml", 12);
            progress.failed("b.yaml", 34, "boom", FailureKind.SETUP);
            progress.validated("c.yaml");
            progress.invalid("d.yaml", "bad step");
            List<String> lines = bytes.toString(charset).lines().toList();
            assertThat(lines.get(0)).isEqualTo("[OK] a.yaml  12 ms");
            assertThat(lines.get(1)).startsWith("[FAIL] b.yaml  34 ms ").endsWith(" boom · setup problem");
            assertThat(lines.get(2)).isEqualTo("[OK] c.yaml");
            assertThat(lines.get(3)).startsWith("[FAIL] d.yaml ").endsWith(" bad step");
        }
    }
```

- [x] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -pl idm-test-runner -Dtest=ConsoleProgressTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL in `testFallbackMarksWhenCharsetCannotEncodeSymbols` because `? a.yaml  12 ms` is printed instead of `[OK] a.yaml  12 ms`.

- [x] **Step 3: Implement the charset check in `ConsoleProgress.java`**

In `ConsoleProgress(PrintStream out, Locale locale)`, check `boolean unicode = out.charset().newEncoder().canEncode("✔✖");`, store `private final String ok` (`unicode ? "✔" : "[OK]"`) and `private final String fail` (`unicode ? "✖" : "[FAIL]"`), and use `ok + " "` and `fail + " "` in `passed`, `failed`, `validated`, and `invalid`.

- [x] **Step 4: Run `idm-test-runner` tests to verify they pass**

Run: `./mvnw test -pl idm-test-runner -Dtest=ConsoleProgressTest,ValidateCommandTest,RunCommandTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS

- [x] **Step 5: Update Windows CI smoke test in `.github/workflows/runner-dist.yml` and docs in `docs/guides/runner.md`**

In `.github/workflows/runner-dist.yml`, update the `Smoke test (Windows)` step so `validate` writes to `%RUNNER_TEMP%\validate.txt`, echoes it via `type`, and verifies with `findstr /b /c:"[OK] example.yaml" /c:"✔ example.yaml" "%RUNNER_TEMP%\validate.txt" || exit /b 1`.

In `docs/guides/runner.md` around line 192, add one sentence mentioning that when the console's character encoding cannot display `✔` and `✖` (such as the default Windows console code page), `idmtest` prints `[OK]` and `[FAIL]` instead.

- [x] **Step 6: Commit**

```bash
git add docs/superpowers/specs/2026-11-01-itf-59-console-progress-ascii-fallback-design.md \
        docs/superpowers/plans/2026-11-01-itf-59-console-progress-ascii-fallback.md \
        idm-test-runner/src/main/java/com/idm/test/runner/ConsoleProgress.java \
        idm-test-runner/src/test/java/com/idm/test/runner/ConsoleProgressTest.java \
        .github/workflows/runner-dist.yml \
        docs/guides/runner.md
git commit -m "fix(runner): fall back to [OK]/[FAIL] when console charset cannot encode ✔/✖ (ITF-59)"
```
