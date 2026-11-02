# ITF-59: Consoleprogress ASCII fallback for non-Unicode console streams

## Intent

When `idmtest validate` or `idmtest run` writes to a console stream whose charset cannot encode `✔` (`U+2714`) and `✖` (`U+2716`) — such as a Windows console using code page 437, 850, or 1252 — Java replaces those characters with `?` (`? example.yaml`). Falling back to `[OK]` and `[FAIL]` keeps console output readable on non-Unicode code pages while preserving `✔` and `✖` on UTF-8 streams.

## Goals, non-goals, and anti-goals

- **Goals:**
  - Detect in `ConsoleProgress` whether `out.charset()` can encode `✔` and `✖`.
  - Print `✔` / `✖` when the stream's charset supports them, and `[OK]` / `[FAIL]` when it does not, in `passed`, `failed`, `validated`, and `invalid`.
  - Test both UTF-8 and non-Unicode (`US-ASCII` / `windows-1252`) streams in `ConsoleProgressTest`.
  - Assert in the Windows CI smoke step (`.github/workflows/runner-dist.yml`) that `idmtest validate` prints either `[OK] example.yaml` or `✔ example.yaml`.
  - Document the `[OK]` / `[FAIL]` fallback in one line in `docs/guides/runner.md`.
- **Non-goals & anti-goals:**
  - No changes to the rest of the line text, summary lines, exit codes, HTML/JSON reports, or `run.log`.

## Approaches

1. **Check `out.charset().newEncoder().canEncode("✔✖")` in `ConsoleProgress` (selected):**
   Inspect the `PrintStream`'s charset once in the `ConsoleProgress` constructor and select `"✔"` / `"✖"` or `"[OK]"` / `"[FAIL]"`. Matches how `RunCommand`, `ValidateCommand`, and unit tests construct `ConsoleProgress(out, locale)` without touching global system properties.
2. **Query `System.console()` or `stdout.encoding` directly (not selected):**
   Ignores the charset of the `PrintStream` passed to `ConsoleProgress`, making unit tests rely on global state rather than stream encoding.

## How it gets built

- **`ConsoleProgress` (`idm-test-runner`):** In the constructor, check `out.charset().newEncoder().canEncode("✔✖")` (your call). Store `ok` (`"✔"` or `"[OK]"`) and `fail` (`"✖"` or `"[FAIL]"`) and use them as the line prefixes in `passed`, `failed`, `validated`, and `invalid`.
- **`ConsoleProgressTest`:** Keep existing UTF-8 assertions (`✔` / `✖`) and add tests verifying `passed`, `failed`, `validated`, and `invalid` with a `UTF-8` stream (`✔` / `✖`) and with non-Unicode streams (`US-ASCII` and `windows-1252` → `[OK]` / `[FAIL]`).
- **Windows CI smoke step (`.github/workflows/runner-dist.yml`):** Capture `idmtest validate` output in the Windows job, print it, and verify a line starts with `[OK] example.yaml` or `✔ example.yaml`.
- **Runner guide (`docs/guides/runner.md`):** Add one sentence in the *Validate and run* section noting that `✔` and `✖` fall back to `[OK]` and `[FAIL]` when the console encoding cannot display them.

## Left to the builder

- Exact field names and helper structure inside `ConsoleProgress` and `ConsoleProgressTest`, and exact `findstr` invocation in `runner-dist.yml`.
