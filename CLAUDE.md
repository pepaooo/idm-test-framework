# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is an **integration testing framework** for Identity Management (IdM) systems, designed to test provisioning workflows across LDAP, databases, REST APIs, and SOAP services. Think of it as similar to OpenText Identity Validator but built on modern Spring Boot and JUnit.

**Critical Context**: See `docs/reference/specification.md` for the complete architectural specification and design decisions. This file focuses on day-to-day development commands and architectural principles.

## Core Architectural Principles

### What We DO
- **Use Spring's built-in clients**: RestClient, RestTemplate, WebClient (NO custom REST wrappers)
- **Use Spring Security OAuth2 Client**: For automatic token management (NO custom OAuth managers)
- **Raw SQL with JdbcTemplate**: For database validation (NO JPA/Hibernate entities)
- **Tests as orchestrators**: Each test explicitly shows its workflow (NO hidden orchestration layers)
- **Integration-first**: 90% integration tests against real systems, 10% unit tests for utilities only

### What We DON'T DO
- ❌ Custom REST client wrappers (Spring provides excellent ones)
- ❌ Custom OAuth token managers (Spring Security handles this)
- ❌ JPA/Hibernate for test validation queries (raw SQL is more flexible)
- ❌ Orchestration layers that hide test logic in the Java tier (tests must be explicit) — the declarative YAML tier is a deliberate exception; see `docs/reference/specification.md`
- ❌ Mocking external systems in integration tests (use real systems or Testcontainers)

## Development Commands

### Build & Run
```bash
# Build the project
./mvnw clean install

# Run tests
./mvnw test

# Run specific test class
./mvnw test -Dtest=UserProvisioningWorkflowTest

# Run specific test method
./mvnw test -Dtest=UserProvisioningWorkflowTest#testUserProvisioningToLdap

# Run one YAML scenario, or a glob-selected group (quote patterns containing * or braces)
./mvnw test -pl idm-test-scenarios -am -Dtest=YamlScenarioTest -Dsurefire.failIfNoSpecifiedTests=false -Dscenario=provisioning-user-to-ldap.yaml
./mvnw test -pl idm-test-scenarios -am -Dtest=YamlScenarioTest -Dsurefire.failIfNoSpecifiedTests=false -Dscenario='provisioning-*'

# Run the YAML corpus against OpenLDAP/PostgreSQL/WireMock containers (optional; needs Docker)
./mvnw test -pl idm-test-scenarios -am -Dtest=ContainerScenarios -Dsurefire.failIfNoSpecifiedTests=false

# Skip tests during build
./mvnw clean install -DskipTests

# Run with debug logging
./mvnw test -Dlogging.level.com.idm.test=DEBUG
```

### IDE Integration
```bash
# IntelliJ IDEA: Import as Maven project
# Right-click on test class → Run 'ClassName'
# Right-click on test method → Run 'methodName'

# VS Code: Install "Java Extension Pack"
# Tests are automatically discovered and runnable from Test Explorer
```

## Project Structure

```
idm-test-framework/
├── pom.xml                  # Maven parent POM (multi-module)
├── idm-test-core/           # TestExecutor (waitUntil, executeWithRetry), OperationResult, ErrorType
├── idm-test-utils/          # Test data generators, assertions, EmbeddedLdapServer
├── idm-test-engine/         # YAML scenario engine: loader, validator, engine, 14 step types (Spring-free)
├── idm-connector-ldap/      # eDirectory/LDAP (EdirectoryConnector)
├── idm-connector-database/  # Multi-DB raw SQL (MultiDatabaseConnector)
├── idm-connector-rest/      # IdmRestConnector, RestAuthenticator (basic/OAuth2), JDK HTTP client
├── idm-connector-soap/      # Role and Resource service connectors (JAX-WS)
├── idm-connector-idm/       # Placeholder, no sources
├── idm-test-scenarios/      # Java workflow tests; YAML corpus (scenarios/, scenarios-real/);
│                            # runners: YamlScenarioTest, ContainerScenarios, RealEnvironmentScenarios
└── docs/                    # reference/, guides/, architecture/, specs/ and plans/ per ticket
```

Specs live in `docs/specs/YYYY-MM-DD-*-design.md` and plans in `docs/plans/YYYY-MM-DD-*.md`.

## Key Components

### 1. TestExecutor (Core Utility)
Location: `idm-test-core/src/main/java/com/idm/test/core/engine/TestExecutor.java`

Purpose: Provides `waitUntil()` and `executeWithRetry()` for async provisioning validation.

### 2. Connectors (Simple, Focused)
Each connector does ONE thing:
- **EdirectoryConnector**: LDAP CRUD operations
- **MultiDatabaseConnector**: Execute raw SQL on any configured database
- **IdmRestConnector**: Thin wrapper around Spring's RestClient
- **RestAuthenticator**: Authorization header from a REST target's settings (basic, or OAuth2 client credentials via Spring Security)
- **RoleServiceConnector** / **ResourceServiceConnector**: SOAP clients for the NetIQ Role and Resource services

### 3. Test Scenarios (Explicit Workflows)
Tests orchestrate their own flows step-by-step:
```java
@Test
void testUserProvisioning() {
    // STEP 1: Create user in IdM
    idmRestConnector.post("/users", request, CreateUserResponse.class);

    // STEP 2: Wait and verify in eDirectory
    testExecutor.waitUntil(() -> edirectoryConnector.read(dn).isSuccess(), ...);

    // STEP 3: Assign role via SOAP
    roleServiceConnector.assignRole(request);

    // STEP 4: Wait for provisioning to target app
    testExecutor.waitUntil(() -> checkTargetAccount(), ...);

    // STEP 5: Verify final state
    assertThat(targetAccount).hasExpectedAttributes();
}
```

## Configuration Approach

### Use application.yml (not .properties)
The framework requires complex nested configuration for multiple databases, OAuth clients, and LDAP servers. YAML is more readable for this.

Example structure:
```yaml
idm:
  test:
    ldap:
      url: ldap://localhost:389
      base-dn: dc=company,dc=com

    databases:
      sap:
        jdbc-url: jdbc:oracle:thin:@localhost:1521:SAP
        username: ${SAP_USER}
        password: ${SAP_PASS}

      oracle-hr:
        jdbc-url: jdbc:oracle:thin:@localhost:1521:HRDB
        username: ${HR_USER}
        password: ${HR_PASS}

spring:
  security:
    oauth2:
      client:
        registration:
          app1:
            client-id: ${APP1_CLIENT_ID}
            client-secret: ${APP1_CLIENT_SECRET}
            authorization-grant-type: client_credentials
```

## Dependencies

These are in place; add others only when a module needs them:

### Core Testing
- spring-boot-starter-test (already present)
- assertj-core (better assertions than JUnit)
- testcontainers (for embedded test systems)

### Connectors
- spring-ldap-core + unboundid-ldapsdk (LDAP)
- spring-boot-starter-jdbc (databases)
- spring-boot-starter-web-services (SOAP)
- spring-boot-starter-security-oauth2-client (OAuth)
- spring-boot-starter-restclient (REST)

### Database Drivers (as needed)
- oracle-jdbc (Oracle)
- postgresql (PostgreSQL)
- h2 (embedded testing)

## Testing Philosophy

### Integration Tests (90% of tests)
- Test against real systems or Testcontainers
- Full end-to-end workflows
- No mocking of external systems
- Located in: `idm-test-scenarios/src/test/java/`

### Unit Tests (10% of tests)
- ONLY for utilities and helpers
- Data generators, string formatters, date utilities
- Located alongside utility classes

### Test Naming Convention
```java
@DisplayName("Complete workflow: Create → Assign → Verify")
void testCompleteProvisioningWorkflow()

@DisplayName("Should retry failed operations with exponential backoff")
void testExecutorRetriesOperation()
```

## Common Patterns

### Using Spring's RestClient (Correct Way)
```java
@Component
public class IdmRestConnector {
    private final RestClient restClient;

    public <T> OperationResult<T> post(String endpoint, Object body, Class<T> responseType) {
        T response = restClient.post()
            .uri(baseUrl + endpoint)
            .body(body)
            .retrieve()
            .body(responseType);
        return OperationResult.success(response);
    }
}
```

### Database Validation (Raw SQL)
```java
@Component
public class MultiDatabaseConnector {
    private final Map<String, JdbcTemplate> jdbcTemplates;

    public OperationResult<List<Map<String, Object>>> executeQuery(
            String dbName, String sql, Object... params) {
        JdbcTemplate template = jdbcTemplates.get(dbName);
        List<Map<String, Object>> results = template.queryForList(sql, params);
        return OperationResult.success(results);
    }
}
```

### Async Provisioning Validation
```java
boolean provisioned = testExecutor.waitUntil(
    () -> {
        OperationResult<User> result = connector.getUser(userId);
        return result.isSuccess() && result.getData().isPresent();
    },
    Duration.ofMinutes(5),   // timeout
    Duration.ofSeconds(5)     // poll interval
);
```

## Development Workflow

1. **Start with docs/reference/specification.md** and `docs/architecture/capabilities.md` for what exists
2. **One ticket at a time**: design in `docs/specs/`, plan in `docs/plans/`, implementation on a feature branch
3. **Write integration tests alongside the change**: against embedded services or WireMock, never mocks of our own code
4. **Keep the docs honest**: update the authoring guide, capability matrix and README when behaviour changes

## Important Notes

- **Java 25 LTS**: Use modern Java features (records, pattern matching, switch expressions)
- **Spring Boot 4.1.1**: Latest stable version with Spring Framework 7.0.x
- **No Lombok**: Use Java records instead
- **Logback for logging**: SLF4J facade with Logback implementation
- **Maven over Gradle**: Better enterprise tooling support

## Reference

- Full specification: `docs/reference/specification.md`
- Spring Boot docs: https://docs.spring.io/spring-boot/4.1/reference/
- Spring Framework docs: https://docs.spring.io/spring-framework/reference/
- JUnit docs: https://docs.junit.org/current/user-guide/
