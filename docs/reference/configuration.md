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

**Environment Variables:**
- `IDM_LDAP_URL` - LDAP server URL
- `IDM_LDAP_BASE_DN` - Base DN for searches
- `IDM_LDAP_USER_DN` - Admin user DN
- `IDM_LDAP_PASSWORD` - Admin password

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
      username: ${IDM_SOAP_USER}
      password: ${IDM_SOAP_PASSWORD}
      connection-timeout: 10000
      read-timeout: 30000
      message-logging: false
```

**Environment Variables:**
- `IDM_SOAP_URL` - SOAP service endpoint
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

**Authentication.** `rest.request`, `rest.verify` and `http.request` authenticate with the
target's settings, and nothing is written in the scenario:

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
      max-retries: 3
      retry-delay-ms: 1000
      ldap-provision-timeout-seconds: 120
      database-provision-timeout-seconds: 180
      rest-provision-timeout-seconds: 300
```

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
`read-timeout` are accepted but not yet applied; the shared client uses 10 s and 30 s.

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
- For LDAPS (secure), ensure certificates are trusted

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
