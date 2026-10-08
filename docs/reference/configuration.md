# Configuration Guide

This document explains how to configure the IdM Test Framework for your environment.

## Configuration File Location

The main configuration file is `application.yml` located in:
- `idm-test-scenarios/src/test/resources/application.yml`

This file serves as the template for all environments.

## Configuration Structure

```yaml
idm:
  test:
    ldap:          # eDirectory / LDAP configuration
    databases:     # Multiple database connections
    soap:          # SOAP service (Role Service, Resource Service)
    rest:          # REST API endpoints
    executor:      # Test execution settings
```

## Environment-Specific Configuration

The framework supports multiple profiles:

- **default** - Local development with embedded/mock services
- **dev** - Development environment with real services
- **test** - CI/CD test environment (uses H2, WireMock)
- **prod** - Production-like integration environment

Activate a profile:
```bash
export IDM_TEST_PROFILE=dev
mvn test
```

Or in your IDE/Maven command:
```bash
mvn test -Dspring.profiles.active=dev
```

## Sensitive Data Management

**NEVER commit passwords or secrets to version control!**

Use environment variables for sensitive data:

```yaml
idm:
  test:
    ldap:
      password: ${IDM_LDAP_PASSWORD:default-for-local-dev}
```

Set environment variables:

### Windows
```cmd
set IDM_LDAP_PASSWORD=your-password
set SAP_CLIENT_SECRET=your-secret
```

### Linux/Mac
```bash
export IDM_LDAP_PASSWORD=your-password
export SAP_CLIENT_SECRET=your-secret
```

### CI/CD
Configure secrets in your CI/CD platform (GitHub Actions, GitLab CI, Jenkins, etc.)

## Configuration by Connector

### LDAP Configuration

```yaml
idm:
  test:
    ldap:
      url: ldap://localhost:389
      base-dn: dc=company,dc=com
      user-dn: cn=admin,dc=company,dc=com
      password: ${IDM_LDAP_PASSWORD}
      pooled: true
      min-idle: 1
      max-active: 8
```

| Key | Default | Meaning |
|---|---|---|
| `url` | — | `ldap://host:389` or `ldaps://host:636` |
| `base-dn` | — | the base every `ldap.*` step's DN is relative to |
| `user-dn`, `password` | — | the bind account; both blank binds anonymously |
| `pooled` | `true` | pool connections (Spring LDAP `PoolingContextSource`) |
| `min-idle` | `1` | idle connections kept open |
| `max-active` | `8` | connections in use at once |
| `max-total` | `8` | connections in total |
| `max-wait` | `-1` | milliseconds to wait for a free connection; `-1` waits indefinitely |

**Environment Variables:**
- `IDM_LDAP_URL` - LDAP server URL
- `IDM_LDAP_BASE_DN` - Base DN for searches
- `IDM_LDAP_USER_DN` - Admin user DN
- `IDM_LDAP_PASSWORD` - Admin password

#### Several directories

To check what IdM provisions into more than one directory, name each under
`idm.test.directories`, with the same keys as `idm.test.ldap`:

```yaml
idm:
  test:
    default-directory: edirectory      # used by ldap.* steps without directory:
    directories:
      edirectory:
        url: ldaps://edir.company.com:636
        base-dn: o=company
        user-dn: cn=admin,o=company
        password: ${EDIR_PASSWORD}
      ad:
        url: ldaps://dc01.company.com:636
        base-dn: dc=corp,dc=company,dc=com
        user-dn: CN=svc-idmtest,OU=Service,DC=corp,DC=company,DC=com
        password: ${AD_PASSWORD}
        pooled: false
```

- **The `idm.test.ldap` block above is the directory named `default`,** so existing
  configurations keep working. A `directories.default` entry replaces it (a warning is logged).
- **The default directory** is `default-directory` when set (it must name a configured
  directory, or startup fails), otherwise the only directory, otherwise `default`.
- **The directory `default` always exists** in the scenarios module, because the shipped
  `application.yml` always defines the `idm.test.ldap` block. Once you add named directories,
  set `default-directory` unless the old block is the directory your steps (and the Java tier)
  should use; otherwise steps without `directory:` go to the old block, not to "the only
  named directory".
- **Name directories with lower-case letters and digits.** The environment-variable form
  (`IDM_TEST_DIRECTORIES_<NAME>_URL`) upper-cases the name and drops dashes, so other names
  cannot be set from environment variables reliably.
- **Nothing connects at startup:** a directory's connection opens when a step first uses it.
- **Scenarios choose** with `directory: <name>`; see the
  [authoring reference](writing-scenarios.md#ldap).
- **Environment variables** follow Spring's rules, for example
  `IDM_TEST_DIRECTORIES_AD_URL` and `IDM_TEST_DIRECTORIES_AD_PASSWORD`.

### Database Configuration

Configure multiple databases for testing provisioning to different systems:

```yaml
idm:
  test:
    databases:
      sap:
        jdbc-url: ${SAP_DB_URL:jdbc:postgresql://localhost:5432/sap}
        username: ${SAP_DB_USER}
        password: ${SAP_DB_PASSWORD}
        driver-class-name: org.postgresql.Driver
        max-pool-size: 5

      oracle-hr:
        jdbc-url: ${HR_DB_URL:jdbc:oracle:thin:@localhost:1521:HRDB}
        username: ${HR_DB_USER}
        password: ${HR_DB_PASSWORD}
        driver-class-name: oracle.jdbc.OracleDriver
        max-pool-size: 5
```

| Key | Default | Meaning |
|---|---|---|
| `jdbc-url`, `username`, `password` | — | the connection |
| `driver-class-name` | — | the JDBC driver class, for example `org.postgresql.Driver`; set it for every database |
| `max-pool-size` | `10` | connections at most (HikariCP) |
| `min-idle` | `2` | idle connections kept |
| `connection-timeout` | `30000` | milliseconds to wait for a connection |
| `idle-timeout` | `600000` | milliseconds before an idle connection closes |
| `max-lifetime` | `1800000` | milliseconds before a connection is replaced |

A database's pool opens the first time a step uses it (except the embedded `test-db`).

**Environment Variables per Database:**
- `<NAME>_DB_URL` - JDBC connection URL
- `<NAME>_DB_USER` - Database username
- `<NAME>_DB_PASSWORD` - Database password

**Supported Databases:**
- PostgreSQL
- Oracle
- SQL Server
- MySQL/MariaDB
- H2 (for testing)

### SOAP Configuration

For NetIQ Identity Manager SOAP services (Role Service, Resource Service):

```yaml
idm:
  test:
    soap:
      url: ${IDM_SOAP_URL:https://idm.company.com:8543/IDMProv/role/service}
      resource-url: https://idm.company.com:8543/IDMProv/resource/service
      username: ${IDM_SOAP_USER}
      password: ${IDM_SOAP_PASSWORD}
      connection-timeout: 10000
      read-timeout: 30000
```

| Key | Used by | Meaning |
|---|---|---|
| `url` | `roles.grant`, `roles.revoke` | the Role service endpoint; without it, those steps fail without calling anything |
| `resource-url` | `resources.grant`, `resources.revoke` | the Resource service endpoint; without it, those steps fail without calling anything |
| `username`, `password` | both services | HTTP Basic credentials; set both, or neither for an unauthenticated endpoint (only one fails at startup) |
| `connection-timeout`, `read-timeout` | both services | milliseconds to connect, and to wait for data from the service (defaults 10 000 and 30 000); must be positive, or startup fails. A call that exceeds them fails its step. For SOAP, `read-timeout` limits each wait for the next data, not the whole call |

**Environment Variables:**
- `IDM_SOAP_URL` - Role service endpoint
- `IDM_TEST_SOAP_RESOURCE_URL` - Resource service endpoint (Spring's naming; the shipped
  `application.yml` has no placeholder for it)
- `IDM_SOAP_USER` - Service username
- `IDM_SOAP_PASSWORD` - Service password

### REST API Configuration

Configure multiple REST APIs (IdM, SAP, Workday, ServiceNow, etc.):

```yaml
idm:
  test:
    rest:
      idm:
        base-url: ${IDM_REST_URL:https://idm.company.com:8543/IDMProv}
        username: ${IDM_REST_USER}
        password: ${IDM_REST_PASSWORD}
        connect-timeout: 10000
        read-timeout: 30000

      sap:
        base-url: ${SAP_REST_URL}
        use-oauth: true
        oauth-client-name: sap
        connect-timeout: 15000
        read-timeout: 45000
```

`connect-timeout` and `read-timeout` (milliseconds; defaults 10 000 and 30 000) apply to every
`rest.*` and `http.*` step on that target. `read-timeout` is the overall deadline for the response.
A request that exceeds it fails like an unreachable server (retried under `wait:`); a value that is
not positive fails the step at once, before anything is sent.

**Authentication.** `rest.request`, `rest.verify`, `http.request` and `http.verify` authenticate
with the target's settings, and nothing is written in the scenario:

| Target settings | Header sent |
|---|---|
| `use-oauth: true` and `oauth-client-name: <registration>` | `Authorization: Bearer <token>`, a client-credentials token from that `spring.security.oauth2.client.registration` entry, cached and renewed by Spring Security |
| `username` and `password` | `Authorization: Basic ...` |
| neither | none |

Only one of `username`/`password`, or `use-oauth` naming an unknown registration, fails the step
before anything is sent. A step that sets its own `Authorization` header sends exactly that. In
`application.yml`, `idm` uses basic authentication and `sap`, `workday` and `servicenow` use OAuth2.

**Basic Auth Environment Variables:**
- `<API>_REST_URL` - REST API base URL
- `<API>_REST_USER` - Username (if using basic auth)
- `<API>_REST_PASSWORD` - Password (if using basic auth)

**OAuth2 Configuration:**

For APIs using OAuth2, configure under `spring.security.oauth2.client`:

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          sap:
            client-id: ${SAP_CLIENT_ID}
            client-secret: ${SAP_CLIENT_SECRET}
            authorization-grant-type: client_credentials
            scope: api.read,api.write
        provider:
          sap:
            token-uri: ${SAP_TOKEN_URI}
```

**OAuth2 Environment Variables:**
- `<API>_CLIENT_ID` - OAuth2 client ID
- `<API>_CLIENT_SECRET` - OAuth2 client secret
- `<API>_TOKEN_URI` - Token endpoint URL

### Test Executor Configuration

Controls test execution behavior:

```yaml
idm:
  test:
    executor:
      default-timeout-seconds: 300        # 5 minutes
      default-poll-interval-seconds: 5
      ldap-provision-timeout-seconds: 120
      database-provision-timeout-seconds: 180
```

`default-timeout-seconds` and `default-poll-interval-seconds` are the wait used by YAML
scenarios' `wait: true`, and by a `wait:` map that leaves `timeout` or `poll` out (the shipped
`test` profile sets them to 60 s and 1 s). `ldap-provision-timeout-seconds` and
`database-provision-timeout-seconds` are how long the Java workflow tests wait for provisioning to
reach the directory and the databases.

## TLS certificates (LDAPS, HTTPS)

The framework has no TLS settings of its own. Every connector uses the **test JVM's default
truststore**:

| Connector | TLS used for |
|---|---|
| LDAP (`EdirectoryConnector`, via Spring LDAP / JNDI) | `ldaps://` URLs |
| REST and `http.request` (JDK `HttpClient`) | `https://` targets, and OAuth2 token requests |
| SOAP role and resource services (JAX-WS) | `https://` service URLs |

A server whose certificate is signed by a public CA the JDK already trusts needs nothing. For a
server with an internal or self-signed certificate, give the JVM a truststore that contains the
**CA certificate** that signed it (preferred, because it survives a server-certificate renewal) or
the server certificate itself.

**1. Create a truststore file.** Keep it outside the repository.

```bash
keytool -importcert -alias edirectory-ca -file edirectory-ca.pem -keystore idm-truststore.p12 -storetype PKCS12 -storepass changeit -noprompt
```

Repeat `-importcert` with another `-alias` for each further CA (another directory, an internal
REST or SOAP server, a token server).

**2. Point the test JVM at it.** Surefire passes `-D` properties from the Maven command line to the
forked test JVM:

```bash
./mvnw -pl idm-test-scenarios -am test -Dtest=RealEnvironmentScenarios -Dsurefire.failIfNoSpecifiedTests=false -Djavax.net.ssl.trustStore=/path/to/idm-truststore.p12 -Djavax.net.ssl.trustStorePassword=changeit -Djavax.net.ssl.trustStoreType=PKCS12
```

In an IDE, add the same three `-Djavax.net.ssl.*` options to the run configuration's VM options.

**Things to know:**

- **`javax.net.ssl.trustStore` replaces the JDK's default truststore; it does not add to it.**
  The JVM then trusts only what is in your file, for LDAP, REST, SOAP and OAuth alike. If any
  endpoint uses a public certificate (for example a SaaS REST API or its token server), either
  import that CA too, or start from a copy of the JDK's `cacerts` and add your CA to it:

  ```bash
  cp "$JAVA_HOME/lib/security/cacerts" idm-truststore.p12
  ```

  ```bash
  keytool -importcert -alias edirectory-ca -file edirectory-ca.pem -keystore idm-truststore.p12 -storepass changeit -noprompt
  ```

- **The hostname must match the certificate.** Java checks the LDAPS server's hostname against
  the certificate's subject alternative names. Use a name the certificate covers, for example
  `ldaps://edir.company.com:636`, not an IP address it doesn't list.
- **Use `ldaps://` (port 636).** StartTLS (`ldap://` upgraded to TLS) is not configured on the
  LDAP context source.
- **Importing into the JDK's own `cacerts`** also works, but it affects every Java program on the
  machine, usually needs administrator rights, and is lost when the JDK is upgraded. A separate
  file passed with `-D` is preferred.
- **Mutual TLS:** only if a server requires a client certificate, also pass
  `-Djavax.net.ssl.keyStore=…`, `-Djavax.net.ssl.keyStorePassword=…` and
  `-Djavax.net.ssl.keyStoreType=PKCS12`. Username/password binds and basic or OAuth2
  authentication don't need it.
- **Diagnosing a handshake failure:** add `-Djavax.net.debug=ssl:handshake` to see which
  certificate the server sent and why it was rejected. `PKIX path building failed` means the
  signing CA is not in the truststore; `No subject alternative names matching` means the hostname
  does not match.

## Profile Examples

### Local Development (default)

```yaml
# Uses embedded services, mocks, or localhost
idm:
  test:
    ldap:
      url: ldap://localhost:389
    databases:
      test-db:
        jdbc-url: jdbc:h2:mem:testdb
    soap:
      url: http://localhost:8090  # WireMock
```

### Development Environment (dev)

```yaml
spring:
  config:
    activate:
      on-profile: dev

idm:
  test:
    ldap:
      url: ldap://dev-edirectory.company.com:389
    databases:
      sap:
        jdbc-url: jdbc:postgresql://dev-db.company.com:5432/sap_dev
```

### CI/CD Testing (test)

```yaml
spring:
  config:
    activate:
      on-profile: test

# Uses Testcontainers, H2, WireMock
idm:
  test:
    databases:
      sap:
        jdbc-url: jdbc:h2:mem:sap_test;MODE=PostgreSQL
```

### Production-Like (prod)

```yaml
spring:
  config:
    activate:
      on-profile: prod

idm:
  test:
    ldap:
      url: ldaps://prod-edirectory.company.com:636  # Secure LDAP
    databases:
      sap:
        jdbc-url: jdbc:postgresql://prod-db.company.com:5432/sap
        max-pool-size: 10
```

## Adding a New Database

1. Add configuration to `application.yml`:

```yaml
idm:
  test:
    databases:
      my-new-app:
        jdbc-url: ${MYAPP_DB_URL}
        username: ${MYAPP_DB_USER}
        password: ${MYAPP_DB_PASSWORD}
        driver-class-name: org.postgresql.Driver
        max-pool-size: 5
```

2. Add driver dependency to `pom.xml` (if not already present):

```xml
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
</dependency>
```

3. Use in tests:

```java
databaseConnector.executeQuery("my-new-app", "SELECT * FROM users WHERE id = ?", userId);
```

## Adding a New REST API

1. Add configuration to `application.yml`:

```yaml
idm:
  test:
    rest:
      my-api:
        base-url: ${MYAPI_URL}
        username: ${MYAPI_USER}
        password: ${MYAPI_PASSWORD}
        connect-timeout: 10000
        read-timeout: 30000
```

Give `username` and `password` for basic authentication, `use-oauth: true` with
`oauth-client-name` for OAuth2, or neither for an unauthenticated API. `connect-timeout` and
`read-timeout` (milliseconds; defaults 10 000 and 30 000) are applied per target; see above.

2. Use it from a scenario by name. Nothing else needs wiring: the steps resolve the base URL and
   add the `Authorization` header from these settings.

```yaml
- rest.request:
    target: my-api
    method: GET
    path: /users/${user.userId}
    as: found
```

A real-environment run checks the target before starting (guards 4 and 5); see
[scenarios-real/README.md](../../idm-test-scenarios/src/test/resources/scenarios-real/README.md).

## Validation

Test your configuration:

```bash
./mvnw -pl idm-test-scenarios -am test -Dtest=ConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false
```

This will verify all properties load correctly.

## Troubleshooting

### Configuration not loading

- Check `application.yml` syntax (YAML is sensitive to indentation)
- Verify profile is active: add `-Dspring.profiles.active=yourprofile`
- Check for typos in property names

### Environment variables not working

- Verify variable is set: `echo $VARIABLE_NAME` (Linux/Mac) or `echo %VARIABLE_NAME%` (Windows)
- Restart your IDE/terminal after setting variables
- In CI/CD, check secrets are configured correctly

### Database connection fails

- Verify driver is in dependencies
- Check JDBC URL format for your database type
- Ensure database is accessible from test environment
- Verify credentials are correct

### LDAP connection fails

- Check firewall allows LDAP port (389 or 636)
- Verify DN syntax (must be valid LDAP DN)
- For LDAPS (secure), the server's CA must be in the test JVM's truststore; see [TLS certificates](#tls-certificates-ldaps-https)

## Best Practices

1. **Never commit secrets** - Always use environment variables
2. **Use profiles** - Separate dev, test, and prod configurations
3. **Document custom settings** - Add comments to application.yml
4. **Test configurations** - Run ConfigurationTest after changes
5. **Use defaults wisely** - Provide sensible defaults for local development
6. **Keep timeouts reasonable** - Balance between waiting for provisioning and test speed

## Reference

- Spring Boot Configuration: https://docs.spring.io/spring-boot/reference/features/external-config.html
- Spring Profiles: https://docs.spring.io/spring-boot/reference/features/profiles.html
- Environment Variables: https://12factor.net/config
