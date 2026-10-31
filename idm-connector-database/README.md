# Database connector

Pools start on first use. The default test suite uses H2 and needs no Docker.

## Oracle 21c XE integration test

Requires Java 25 and an x86 Docker environment capable of running
`gvenzl/oracle-xe:21.3.0-slim`. The first run downloads the image and may take
several minutes to initialize Oracle. Startup is bounded to five minutes.
Testcontainers uses your configured Docker endpoint (including `DOCKER_HOST`).

From the repository root:

```sh
./mvnw test -pl idm-connector-database -am -Dtest=OracleConnectionValidationIT -Dsurefire.failIfNoSpecifiedTests=false
```

The test owns and removes its container and closes pools before container teardown,
including on failure. It uses disposable credentials and a dynamically mapped port.
An explicit run fails if Docker or Oracle is unavailable; it does not silently skip.

To run the same assertions against an existing Oracle 21 database, set all three
environment variables. The test only reads database metadata and executes
`SELECT 1 FROM DUAL`; it creates no schema objects and never stops that database.
The password file contains the password, optionally followed by one line ending.

```sh
export ORACLE_IT_JDBC_URL='jdbc:oracle:thin:@//oracle-host:1521/XEPDB1'
export ORACLE_IT_USERNAME='test-user'
export ORACLE_IT_PASSWORD_FILE='/path/to/local/secret'
./mvnw test -pl idm-connector-database -am -Dtest=OracleConnectionValidationIT -Dsurefire.failIfNoSpecifiedTests=false
```

Unset these variables to return to container mode. Do not put the password in the
JDBC URL or command arguments. The direct JDBC probe verifies Oracle major version
21 and valid application SQL before exercising the production pool. A probe failure
is a fixture/connectivity failure, not evidence of the validation bug.

## ITF-16 verification

On 2026-10-31 the external-database mode reproduced the issue on Oracle 21c XE:
the direct JDBC probe passed, then the production pool failed with
`CannotGetJdbcConnectionException` caused by `ORA-00923: FROM keyword not found
where expected`. Oracle reported error code 923 for validation SQL `SELECT 1`.
After removing the hardcoded validation query, the same integration test passed
(1 test, no failures, errors, or skips). The default connector suite and its
reactor dependencies also passed, including the lazy-pool regression checks.
Container mode has not been executed on the local ARM64 Docker environment;
the before/after result was verified against an existing Oracle 21c XE database.

Full-project verification on the same date passed all 1,494 Java tests. The
initial frontend run had one 5-second timeout in `delete asks first and removes
the file` (385 other tests passed). The affected file passed on its own (26/26),
and all frontend tests passed with two workers (386/386), without source or
assertion changes:

```sh
# From idm-test-runner/src/main/frontend, after Maven has installed its Node runtime:
../../../target/node/node node_modules/vitest/vitest.mjs run --maxWorkers=2
```

The initial unmodified full Maven invocation is recorded as a failure due to
that frontend timeout; it is not claimed as a green full build.
