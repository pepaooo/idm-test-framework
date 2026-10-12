# Report and message internationalization (English, Spanish) — design

**Tickets:** epic and tasks under ITF (see §8). **Decided with:** jangulo, 2026-10-12.

## 1. The goal

A Spanish-speaking user reads a run's results in Spanish: the HTML report, its evidence panels, and
the messages that explain each step, validation error and runner guard. Scenario YAML syntax does
not change, and English stays the default.

## 2. Decisions

1. **Two phases, one spec.** Phase 1: the language setting, the report and the evidence panels.
   Phase 2: step, engine, validation and runner messages move to keys.
2. **An explicit setting, English by default.** `idm.test.report.locale` (profile),
   `IDM_TEST_LOCALE`, or `-Didm.test.locale`. Without it the run is English on every machine; the
   JVM default locale is never used.
3. **The JSON `message` stays English.** Each result also records `messageKey` and `messageArgs`,
   so the report — and later a web UI — renders it in any supported language.
4. **A small `Message` type that steps return,** rendered from `.properties` bundles with the JDK's
   `ResourceBundle` and `MessageFormat`. No ICU4J, no render-time translation by pattern matching.
5. **Supported languages:** `en`, `es` (neutral Latin American Spanish, "usted" or impersonal forms;
   DN, LDAP, SQL, JSON, timeout stay as they are). jangulo or a Spanish-speaking colleague reviews
   the Spanish before Phase 2 merges.

## 3. What is translated, and what is not

| Surface | Translated |
|---|---|
| HTML report labels, buttons, status words, `<html lang>`, dates, durations | yes (Phase 1) |
| Evidence panel labels | yes (Phase 1) |
| Step result messages, engine messages (wait, retry, cleanup, fixtures) | yes (Phase 2) |
| Scenario validation errors | yes (Phase 2) |
| Runner failure and real-environment guard messages | yes (Phase 2) |
| Step types, YAML keys, the expression language | no |
| Author-written text: scenario and step names and descriptions | no — shown as written |
| Error text from LDAP, JDBC, HTTP and SOAP servers and drivers | no — passed as a message argument, shown as received |
| Logs (SLF4J) | no — English |
| JSON field names | no — a machine contract |
| User documentation | no — a separate effort if wanted |

## 4. Architecture

**New, in `idm-test-engine` (Spring-free):**
- `com.idm.test.engine.spi.Message` — `record Message(String key, List<Object> args)` with
  `Message.of(String key, Object... args)`. Arguments are plain values (String, Number, Boolean);
  anything else is converted with `String.valueOf` when the message is created, so results stay
  serializable.
- `com.idm.test.engine.i18n.Messages` — loads `com/idm/test/engine/i18n/messages.properties` and
  `messages_es.properties` (UTF-8) through `ResourceBundle`; `String render(Message, Locale)`.
  A key missing in Spanish falls back to English; a key missing in English, or a malformed pattern,
  renders `!key! [args]` and never throws.
- `com.idm.test.engine.i18n.ReportLocale` — `static Locale resolve(String value)`: null or blank →
  `Locale.ENGLISH`; `en` / `es` (case-insensitive) → that locale; anything else throws
  `IllegalArgumentException("unsupported report locale '<value>'; supported: en, es")`.

**Changed:**
- `StepOutcome` gains `success(Message)`, `failure(Message)` and `finalFailure(Message)` next to the
  `String` forms. The engine renders the English text into `StepResult.message` and keeps the key
  and arguments.
- `StepResult`, `ScenarioResult` (its `failureDetail`) and `CleanupResult` gain `messageKey` and
  `messageArgs` (`failureDetailKey` / `failureDetailArgs` for `ScenarioResult`), annotated
  `@JsonInclude(NON_NULL)` so results without a key serialize exactly as today. Existing
  constructors stay.
- `HtmlReportWriter` and `EvidenceHtml` take a `Locale`; every fixed label comes from `report.*` /
  `evidence.*` keys; `<html lang>` is the locale's language; dates use a locale-aware
  `DateTimeFormatter` (`FormatStyle.MEDIUM`); durations keep their units with locale-correct
  decimal separators. A result with a key is rendered in the report's locale; one without a key is
  shown as written.
- Runners (`ScenarioSupport`, `YamlScenarioTest`, `ContainerScenarios`,
  `RealEnvironmentScenarios`): resolve the setting (property, then `IDM_TEST_LOCALE`, then
  `-Didm.test.locale`; the first non-blank wins) once per run with `ReportLocale.resolve`, before
  any scenario runs, and pass the locale to the report writer.

**Key families:** `report.*`, `evidence.*`, `step.<type>.*` (e.g. `step.rest.verify.mismatch`),
`engine.*`, `validation.*`, `runner.*`. Arguments `{0}`, `{1}`, … with MessageFormat apostrophe
escaping; plurals through `{n,choice,…}`.

## 5. Phases

**Phase 1 — setting, report, evidence panels.**
- `Message`, `Messages`, `ReportLocale`; the setting and the runners passing the locale.
- Every `report.*` and `evidence.*` key in English and Spanish.
- Docs: `configuration.md`, `writing-scenarios.md` §7 settings table, `running-tests.md`.
- Result: a Spanish run produces a Spanish report; step messages are still English.
- An English report differs from today's only where dates are now formatted by locale; the tests
  record exactly which strings change.

**Phase 2 — messages.**
- The `StepOutcome` / result changes and the JSON fields.
- Conversion, one task per group: LDAP steps; database steps; REST and HTTP steps; SOAP steps;
  engine (wait, retry, cleanup, fixtures, expressions); validation and runners.
- **Binding rule:** every English `message` stays exactly as it is today, character for character.
  No existing assertion on message text changes; the corpus results prove it (§7).
- When every message is converted, the `String` forms of `StepOutcome` are deprecated so new engine
  messages must use keys. (Deprecation is internal: the `String` forms remain for third-party
  step implementations.)

## 6. Errors

- **Unsupported language** (`fr`, an unknown tag): the run stops before any scenario executes with
  `unsupported report locale 'fr'; supported: en, es`. A blank value means the default (English).
- **Key missing in Spanish:** English is shown. A catalog test makes this impossible to ship.
- **Key missing in English, or malformed pattern:** `!key! [args]`; never an exception, so a broken
  entry cannot hide a failure. Catalog tests catch it at build time.
- **ITF-23 still applies:** message arguments come from the same, already-masked values as today's
  messages, so no password, token, client secret or Authorization value can reach `messageArgs`.

## 7. Testing

- **Catalog consistency:** both files have the same key set; each key has the same placeholders in
  both; every pattern parses; every key used in code exists and every key in the files is used.
- **`Messages`:** English and Spanish rendering, the fallback, `!key!`, apostrophes, choice formats.
- **`ReportLocale`:** default, each source and their precedence, rejection of unsupported values.
- **Report:** a Spanish run's HTML has `lang="es"`, Spanish labels, Spanish dates and translated step
  messages; text without a key (external errors, author text) is unchanged; an English run differs
  from today's only in the recorded date-format strings.
- **JSON:** `messageKey` / `messageArgs` present with a key and absent without one; the English
  `message` of every result in `scenario-results.json` and `multi-directory-results.json` matches a
  baseline taken at the end of Phase 1, field by field (random fixture data masked, as in ITF-45).
- **Secrets:** no `messageArgs` value contains a password, token, client secret or Authorization
  value — extends the existing ITF-23 masking tests.
- **Every task:** the full suite and both corpus counts `0`.

## 8. Tracking

- Epic: "Report and message i18n (English, Spanish)", under ITF-4.
- Tasks: Phase 1 (setting, report, evidence); Phase 2 foundation (`StepOutcome`, results, JSON);
  Phase 2 conversions — LDAP, database, REST/HTTP, SOAP, engine, validation and runners; Spanish
  review.
- Separate ticket: "Decide whether to ship a local web application (ADR)" — brainstormed next; no
  code until decided. The message catalog from this work is what a web UI would reuse.

## 9. Out of scope

- Languages other than English and Spanish (adding one is a new `messages_xx.properties` plus
  `ReportLocale`'s supported list).
- Translating logs, error text from external systems, author-written text or the documentation.
- Re-rendering an existing `scenario-results.json` into another language after the run (the keys
  make it possible; a tool for it is future work, likely the web UI).
