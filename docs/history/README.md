# Historical Reports — Frozen

These documents are **point-in-time records** of past development sessions.
They are not maintained, several are inaccurate, and they contradict each other.

**Do not treat anything here as evidence of current state.** For that, see:

- [Specification](../reference/specification.md) — architecture and design decisions
- [Configuration](../reference/configuration.md) — how to configure the framework
- [Running tests](../reference/running-tests.md) — how to run the suite
- [Specs](../specs/) — design documents for in-flight work
- The build itself — `sh ./mvnw clean test`

Known inaccuracies, recorded so nobody re-derives them:

- `phase-10-complete.md` and `phase-09-complete.md` declare phases complete
  that were not. The root `README.md` repeated those claims while the build
  was failing.
- `phase-10-test-results.md` reports "13/31 passing" and diagnoses a
  `DirContext` pool failure. Those numbers predate a dependency change that
  made the LDAP container unreachable, so the five workflow test classes had
  in fact never run to completion when it was written.
- The Testcontainers/OpenLDAP infrastructure these documents describe was
  removed on 2026-09-11 and replaced with an embedded UnboundID directory.
  See [the green baseline spec](../specs/2026-09-11-suite-green-baseline-design.md).
