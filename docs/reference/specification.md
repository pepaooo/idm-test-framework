# Identity Management Testing Framework - Complete Specification v2.0

## Project Overview

### Purpose
Build a comprehensive **integration testing framework** for Identity Management (IdM) integrations, similar to OpenText Identity Validator, capable of testing provisioning workflows across multiple repository types (LDAP, Databases, REST APIs, SOAP Services).

### Target Use Case
Execute **integration tests against real systems** to validate OpenText Identity Manager integrations with applications:
- Test provisioning from IdM to applications
- Creating and validating objects in IdM/eDirectory
- Assigning roles/resources and validating access
- Verifying account creation/modification in target systems

### Typical End-to-End Flow
1. **User creation in IdM**: Create user object in IdM/eDirectory via REST API (primary) or LDAP (secondary)
2. **Attribute verification**: Validate specific attributes in eDirectory match the creation request
3. **Application assignment**: Invoke SOAP Role Service to assign application/roles/resources using UserApplication endpoint
4. **Provisioning wait**: Wait for IdM provisioning to complete (async operation)
5. **Downstream validation**: Verify account in target application via JDBC, SOAP API, or REST API

### Core Principle
Backend-focused **integration testing framework** using JUnit, with no front-end initially. Tests should be executable from IDE, command line, and CI/CD pipelines. **Tests orchestrate their own workflows explicitly** - no hidden orchestration layers.

---

## Technology Stack

### Core Framework
- **Java**: 25 (LTS)
- **Spring Boot**: 4.1.1 (latest stable)
- **Spring Framework**: 7.0.x (bundled with Spring Boot 4.1.1)
- **Build Tool**: Maven 3.9.x (recommended for enterprise compatibility)

### Testing Framework
- **JUnit 6**: 6.0.x (Jupiter)
- **AssertJ**: 3.27.x (fluent assertions)
- **Mockito**: 5.23.x (minimal usage - only for utilities)
- **Testcontainers**: 2.0.x (containerized test dependencies)

### Repository Connectors
- **LDAP**: Spring LDAP 4.1.x + UnboundID LDAP SDK 7.0.x
- **Database**: Spring JDBC (JdbcTemplate) - **NO JPA/Hibernate for test queries**
- **REST**: Spring RestClient 7.0.x on the JDK `HttpClient` (HTTP/1.1)
- **SOAP**: generated JAX-WS ports + JAXB 4.0.x (not Spring Web Services' `WebServiceTemplate`)
- **OAuth**: Spring Security OAuth2 Client 7.1.x (client credentials, via `RestAuthenticator`)

### Supporting Libraries
- **Logging**: SLF4J 2.0.x + Logback 1.5.x
- **Configuration**: Spring Boot Configuration Processor
- **Validation**: none — no Bean Validation (Jakarta Validation or Hibernate Validator) on the classpath
- **JSON Processing**: Jackson 3.1.x
- **XML Processing**: JAXB 4.0.x
- **Utilities**: Apache Commons Lang3 3.20.x

### Embedded Test Servers
- **LDAP**: UnboundID LDAP SDK (embedded for tests, `EmbeddedLdapServer`)
- **Database**: H2 2.4.x, PostgreSQL (via Testcontainers)
- **Mock Servers**: WireMock 3.x (for REST/SOAP mocking)

---

## Key Architectural Decisions

### ✅ What We're Doing (Best Practices)

1. **Use Spring's Built-in REST Clients**
   - `RestClient` (Spring 6.1+) for modern fluent API
   - `RestTemplate` as fallback
   - `WebClient` for reactive scenarios
   - **NO custom REST client wrappers**

2. **Use Spring Security OAuth2 Client**
   - Automatic token acquisition, caching, and refresh
   - Configuration-driven OAuth setup
   - **NO custom OAuth token managers**

3. **Raw SQL with JdbcTemplate**
   - Execute custom SELECT statements like Validator
   - Results as `List<Map<String, Object>>`
   - Support multiple databases dynamically
   - **NO JPA/Hibernate entities for validation**

4. **Tests as Orchestrators**
   - Each test explicitly defines its workflow
   - Step-by-step execution visible in test code
   - **NO hidden orchestration layers**

5. **Focus on Integration Tests**
   - 90% integration tests against real systems
   - 10% unit tests for utilities only
   - **NO mocking of external systems in integration tests**

### ❌ What We're NOT Doing (Avoiding Over-Engineering)

- ❌ Custom REST client wrappers (use Spring's)
- ❌ Custom OAuth token managers (use Spring Security)
- ❌ JPA entities for database validation (use raw SQL)
- ❌ Orchestration layers hiding workflow logic **in the Java tier** (see "Two authoring tiers" below)
- ❌ Extensive unit testing of integration code
- ❌ Complex abstractions where simple solutions work

---

## Two authoring tiers

The framework offers two ways to write a scenario.

**The Java tier** is explicit: a test orchestrates its own workflow step by step, and nothing
hides what it does. This is the tier the principles above describe.

**The declarative YAML tier** is an orchestration layer, deliberately, so that IdM consultants
who are not Java developers can author scenarios. It trades explicitness for accessibility and
offers two guarantees in exchange:

1. **A scenario's step list is its complete execution order.** There is no hidden setup, no
   implicit ordering and no inherited behaviour. What the file lists is what runs, top to
   bottom.
2. **Expressions compute values; they never act.** A `js:{ }` expression is evaluated in a
   sandbox with no connector access, no filesystem, no network and no Java class access. It
   cannot change the system under test.

Scenarios that cannot be expressed in the YAML vocabulary stay in Java, and are the documented
examples of what that tier is for — `testProvisioningTimeout` and
`testRoleAssignmentFailureHandling` are the current two.

---

## Project Structure

```
idm-test-framework/
│
├── pom.xml                                 # Maven parent POM
├── README.md                               # Project documentation
├── CLAUDE.md                               # This specification file
│
├── idm-test-core/                          # Core testing engine
│   ├── src/main/java/
│   │   └── com/idm/test/core/
│   │       ├── config/                     # Spring configuration
│   │       ├── engine/                     # Test execution utilities
│   │       ├── model/                      # Domain models
│   │       └── exception/                  # Custom exceptions
│   └── src/test/java/
│
├── idm-test-connectors/                    # Simple, focused connectors
│   ├── idm-connector-ldap/                 # eDirectory/LDAP connector
│   ├── idm-connector-database/             # Multi-database JDBC connector
│   ├── idm-connector-rest/                 # REST client configuration
│   ├── idm-connector-soap/                 # SOAP client for Role Service
│   └── idm-connector-idm/                  # IdM REST/SOAP clients
│
├── idm-test-scenarios/                     # Test implementations
│   └── src/test/java/
│       └── com/idm/test/scenarios/
│           ├── provisioning/               # User creation & provisioning
│           ├── modification/               # Updates & changes
│           ├── deprovisioning/             # Termination & cleanup
│           ├── synchronization/            # Sync tests
│           └── integration/                # App-specific workflows
│
└── idm-test-utils/                         # Shared utilities
    └── src/main/java/
        └── com/idm/test/utils/
            ├── data/                       # Test data generators
            ├── assertion/                  # Custom assertions
            └── wait/                       # Wait/retry utilities
```

---

## Architecture Design

### Layered Architecture

```
┌─────────────────────────────────────────────────────────────┐
│              Test Layer (JUnit)                           │
│  Tests orchestrate workflows EXPLICITLY in test code        │
│  - Each step visible and clear                             │
│  - Full control over payloads                              │
│  - No hidden magic                                         │
└─────────────────────────┬───────────────────────────────────┘
                          │
┌─────────────────────────▼───────────────────────────────────┐
│              Test Execution Utilities                       │
│  - TestExecutor (retry, wait, polling)                     │
│  - Custom assertions                                        │
│  - Test data generators                                     │
└─────────────────────────┬───────────────────────────────────┘
                          │
┌─────────────────────────▼───────────────────────────────────┐
│           Simple, Focused Connectors                        │
│  ┌────────────┐  ┌──────────┐  ┌──────────┐  ┌─────────┐  │
│  │ IdM REST   │  │   LDAP   │  │ Database │  │  SOAP   │  │
│  │ Connector  │  │Connector │  │Connector │  │Connector│  │
│  └────────────┘  └──────────┘  └──────────┘  └─────────┘  │
│  Each does ONE thing - no workflow logic                   │
└─────────────────────────┬───────────────────────────────────┘
                          │
┌─────────────────────────▼───────────────────────────────────┐
│           Spring's Built-in Clients                         │
│  - RestClient (Spring 6.1+)                                │
│  - OAuth2AuthorizedClientManager                           │
│  - JdbcTemplate                                            │
│  - WebServiceTemplate                                       │
└─────────────────────────┬───────────────────────────────────┘
                          │
┌─────────────────────────▼───────────────────────────────────┐
│                   Target Systems                            │
│  - IdM REST API                                             │
│  - NetIQ Role Service (SOAP)                               │
│  - eDirectory (LDAP)                                        │
│  - Application Databases (SQL queries)                     │
│  - Application REST APIs                                    │
└─────────────────────────────────────────────────────────────┘
```

---

## Testing Strategy

### Test Distribution

```
┌────────────────────────────────────┐
│     Integration Tests: 90%         │
│  - Tests against real systems      │
│  - End-to-end workflows            │
│  - Multi-system validation         │
└────────────────────────────────────┘

┌────────────────────────────────────┐
│       Unit Tests: 10%              │
│  - Utility classes only            │
│  - Data generators                 │
│  - String/date helpers             │
└────────────────────────────────────┘
```

### Test Organization by Use Case

Each test class covers multiple related use cases:

```
idm-test-scenarios/src/test/java/com/idm/test/scenarios/

├── provisioning/
│   ├── UserCreationTest.java              # UC-001 to UC-010
│   ├── AccountProvisioningTest.java       # UC-011 to UC-020
│   ├── RoleAssignmentTest.java            # UC-021 to UC-030
│   └── ResourceEntitlementTest.java       # UC-031 to UC-040
│
├── modification/
│   ├── UserAttributeUpdateTest.java       # UC-041 to UC-050
│   ├── AccountModificationTest.java       # UC-051 to UC-060
│   └── DepartmentTransferTest.java        # UC-061 to UC-070
│
├── deprovisioning/
│   ├── UserTerminationTest.java           # UC-071 to UC-080
│   ├── AccountDisableTest.java            # UC-081 to UC-090
│   └── AccessRemovalTest.java             # UC-091 to UC-100
│
└── integration/
    ├── SapProvisioningTest.java           # SAP workflows
    ├── OracleHrProvisioningTest.java      # Oracle HR workflows
    └── ServiceNowTest.java                # ServiceNow workflows
```

---

## Core Implementation

### 1. Test Execution Engine

```java
package com.idm.test.core.engine;

import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * Test execution utilities for retry and wait logic
 * Essential for handling async IdM provisioning
 */
@Component
public class TestExecutor {
    
    /**
     * Wait until condition is met or timeout
     * Used extensively for async provisioning validation
     */
    public boolean waitUntil(
            Supplier<Boolean> condition,
            Duration timeout,
            Duration pollInterval) {
        
        long endTime = System.currentTimeMillis() + timeout.toMillis();
        
        while (System.currentTimeMillis() < endTime) {
            if (condition.get()) {
                return true;
            }
            sleep(pollInterval);
        }
        
        return false;
    }
    
    /**
     * Execute operation with retry logic
     */
    public <T> OperationResult<T> executeWithRetry(
            Supplier<OperationResult<T>> operation,
            int maxAttempts,
            Duration delayBetweenAttempts) {
        
        int attempt = 1;
        OperationResult<T> result = null;
        
        while (attempt <= maxAttempts) {
            result = operation.get();
            
            if (result.isSuccess()) {
                return result;
            }
            
            if (attempt < maxAttempts) {
                sleep(delayBetweenAttempts);
            }
            
            attempt++;
        }
        
        return result;
    }
    
    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Sleep interrupted", e);
        }
    }
}
```

### 2. eDirectory/LDAP Connector

```java
package com.idm.test.connector.ldap.client;

import org.springframework.ldap.core.LdapTemplate;
import org.springframework.stereotype.Component;

/**
 * Simple LDAP connector - no workflow logic
 * Just CRUD operations and attribute verification
 */
@Component
public class EdirectoryConnector {
    
    private final LdapTemplate ldapTemplate;
    
    public EdirectoryConnector(LdapTemplate ldapTemplate) {
        this.ldapTemplate = ldapTemplate;
    }
    
    public OperationResult<LdapUser> create(LdapUser user) {
        // Create user in eDirectory
    }
    
    public OperationResult<LdapUser> read(String dn) {
        // Read user from eDirectory
    }
    
    public OperationResult<Boolean> verifyAttributes(
            String dn, 
            Map<String, String> expectedAttributes) {
        // Verify attributes match expected values
    }
    
    public OperationResult<Void> delete(String dn) {
        // Delete user from eDirectory
    }
}
```

### 3. Multi-Database Connector (Raw SQL)

```java
package com.idm.test.connector.database.client;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;

/**
 * Generic database connector using raw SQL
 * Similar to OpenText Validator - execute any SQL query
 */
@Component
public class MultiDatabaseConnector {
    
    private final Map<String, JdbcTemplate> jdbcTemplates;
    
    public MultiDatabaseConnector(Map<String, JdbcTemplate> jdbcTemplates) {
        this.jdbcTemplates = jdbcTemplates;
    }
    
    /**
     * Execute SELECT query on any database
     * Returns results as List<Map<String, Object>>
     */
    public OperationResult<List<Map<String, Object>>> executeQuery(
            String databaseName,
            String sql,
            Object... params) {
        
        try {
            JdbcTemplate template = jdbcTemplates.get(databaseName);
            
            if (template == null) {
                return OperationResult.<List<Map<String, Object>>>builder()
                    .success(false)
                    .message("Database not found: " + databaseName)
                    .build();
            }
            
            List<Map<String, Object>> results = template.queryForList(sql, params);
            
            return OperationResult.<List<Map<String, Object>>>builder()
                .success(true)
                .data(results)
                .message("Query returned " + results.size() + " rows")
                .build();
                
        } catch (Exception e) {
            return OperationResult.<List<Map<String, Object>>>builder()
                .success(false)
                .message("Query failed: " + e.getMessage())
                .build();
        }
    }
    
    /**
     * Verify record exists with expected values
     */
    public OperationResult<Boolean> verifyRecord(
            String databaseName,
            String tableName,
            String keyColumn,
            Object keyValue,
            Map<String, Object> expectedValues) {
        // Verify record values match expected
    }
}
```

### 4. IdM REST Connector (Using Spring's RestClient)

```java
package com.idm.test.connector.idm.client;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * IdM REST connector using Spring's RestClient
 * NO custom wrapper - just a thin helper
 */
@Component
public class IdmRestConnector {
    
    private final RestClient restClient;
    private final String baseUrl;
    
    public IdmRestConnector(RestClient restClient, 
                           @Value("${idm.rest.url}") String baseUrl) {
        this.restClient = restClient;
        this.baseUrl = baseUrl;
    }
    
    public <T> OperationResult<T> post(
            String endpoint, 
            Object body, 
            Class<T> responseType) {
        
        try {
            T response = restClient.post()
                .uri(baseUrl + endpoint)
                .header("Authorization", getAuthHeader())
                .body(body)
                .retrieve()
                .body(responseType);
            
            return OperationResult.<T>builder()
                .success(true)
                .data(response)
                .build();
                
        } catch (Exception e) {
            return OperationResult.<T>builder()
                .success(false)
                .message(e.getMessage())
                .build();
        }
    }
}
```

### 5. SOAP Role Service Connector

```java
package com.idm.test.connector.idm.client;

import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.stereotype.Component;

/**
 * NetIQ Role Service SOAP connector
 * Calls UserApplication endpoint for role assignments
 */
@Component
public class RoleServiceConnector {
    
    private final WebServiceTemplate webServiceTemplate;
    
    public RoleServiceConnector(WebServiceTemplate webServiceTemplate) {
        this.webServiceTemplate = webServiceTemplate;
    }
    
    public OperationResult<AssignRoleResponse> assignRole(
            AssignRoleRequest request) {
        
        try {
            AssignRoleResponse response = (AssignRoleResponse) 
                webServiceTemplate.marshalSendAndReceive(request);
            
            return OperationResult.<AssignRoleResponse>builder()
                .success(response.isSuccess())
                .data(response)
                .build();
                
        } catch (Exception e) {
            return OperationResult.<AssignRoleResponse>builder()
                .success(false)
                .message("SOAP call failed: " + e.getMessage())
                .build();
        }
    }
}
```

---

## Complete Test Example

```java
package com.idm.test.scenarios.provisioning;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Complete user provisioning workflow
 * Test orchestrates its own flow - explicit and clear!
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class UserProvisioningToSapTest {
    
    @Autowired
    private IdmRestConnector idmRestConnector;
    
    @Autowired
    private RoleServiceConnector roleServiceConnector;
    
    @Autowired
    private EdirectoryConnector edirectoryConnector;
    
    @Autowired
    private MultiDatabaseConnector databaseConnector;
    
    @Autowired
    private RestClient restClient;  // Spring's built-in!
    
    @Autowired
    private TestExecutor testExecutor;
    
    private String testUserId;
    
    @BeforeAll
    void setup() {
        testUserId = "test.user." + System.currentTimeMillis();
    }
    
    @Test
    @Order(1)
    @DisplayName("Complete workflow: Create → Assign → Verify")
    void testCompleteProvisioningWorkflow() {
        
        // STEP 1: Create user in IdM via REST
        log.info("Creating user in IdM");
        CreateUserRequest request = new CreateUserRequest(testUserId);
        
        OperationResult<CreateUserResponse> createResult = 
            idmRestConnector.post("/users", request, CreateUserResponse.class);
        
        assertThat(createResult.isSuccess()).isTrue();
        
        
        // STEP 2: Wait and verify in eDirectory
        log.info("Verifying user in eDirectory");
        
        boolean userExists = testExecutor.waitUntil(
            () -> {
                OperationResult<LdapUser> result = 
                    edirectoryConnector.read("cn=" + testUserId + ",ou=users");
                return result.isSuccess();
            },
            Duration.ofSeconds(30),
            Duration.ofSeconds(2)
        );
        
        assertThat(userExists).isTrue();
        
        
        // STEP 3: Assign SAP via SOAP
        log.info("Assigning SAP application");
        
        AssignRoleRequest roleRequest = new AssignRoleRequest(testUserId, "SAP_ACCESS");
        OperationResult<AssignRoleResponse> assignResult = 
            roleServiceConnector.assignRole(roleRequest);
        
        assertThat(assignResult.isSuccess()).isTrue();
        
        
        // STEP 4: Wait for provisioning to SAP database
        log.info("Waiting for SAP provisioning");
        
        boolean provisioned = testExecutor.waitUntil(
            () -> {
                String sql = "SELECT COUNT(*) as cnt FROM SAP_USERS WHERE USERNAME = ?";
                OperationResult<List<Map<String, Object>>> result = 
                    databaseConnector.executeQuery("SAP", sql, testUserId);
                
                if (result.isSuccess() && result.getData().isPresent()) {
                    Number count = (Number) result.getData().get().get(0).get("cnt");
                    return count.intValue() > 0;
                }
                return false;
            },
            Duration.ofMinutes(5),
            Duration.ofSeconds(5)
        );
        
        assertThat(provisioned).isTrue();
        
        
        // STEP 5: Verify in SAP via REST API (Spring's RestClient!)
        log.info("Verifying SAP account via REST");
        
        SapUser sapUser = restClient.get()
            .uri("https://sap.company.com/api/users/{id}", testUserId)
            .header("Authorization", "Basic " + sapCredentials())
            .retrieve()
            .body(SapUser.class);
        
        assertThat(sapUser).isNotNull();
        assertThat(sapUser.getUsername()).isEqualTo(testUserId);
        
        log.info("✅ Complete workflow verified!");
    }
    
    @AfterAll
    void cleanup() {
        // Clean up in reverse order
        databaseConnector.executeUpdate("SAP", 
            "DELETE FROM SAP_USERS WHERE USERNAME = ?", testUserId);
        edirectoryConnector.delete("cn=" + testUserId + ",ou=users");
        idmRestConnector.delete("/users/" + testUserId);
    }
}
```

---

## Configuration

### application.yml

```yaml
spring:
  application:
    name: idm-test-framework
  
  # OAuth2 for applications (Spring handles tokens!)
  security:
    oauth2:
      client:
        registration:
          app1:
            client-id: ${APP1_CLIENT_ID}
            client-secret: ${APP1_CLIENT_SECRET}
            authorization-grant-type: client_credentials
        provider:
          app1:
            token-uri: https://app1.company.com/oauth/token

# LDAP
idm:
  test:
    ldap:
      url: ldap://localhost:389
      base-dn: dc=company,dc=com
      user-dn: cn=admin,dc=company,dc=com
      password: admin
    
    # Multiple databases (raw SQL)
    databases:
      sap:
        jdbc-url: jdbc:oracle:thin:@localhost:1521:SAP
        username: sapuser
        password: sappass
        driver-class-name: oracle.jdbc.OracleDriver
        
      oracle-hr:
        jdbc-url: jdbc:oracle:thin:@localhost:1521:HRDB
        username: hruser
        password: hrpass
        driver-class-name: oracle.jdbc.OracleDriver
    
    # IdM
    idm:
      rest-url: https://idm.company.com:8443/IDMProv
      username: idm_admin
      password: idm_password

logging:
  level:
    com.idm.test: DEBUG
```

---

## Maven POM

```xml
<project>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.1.1</version>
    </parent>
    
    <groupId>com.idm.test</groupId>
    <artifactId>idm-test-framework</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    
    <properties>
        <java.version>25</java.version>
    </properties>
    
    <dependencies>
        <!-- Spring Boot Starters -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter</artifactId>
        </dependency>
        
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-restclient</artifactId>
        </dependency>
        
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-jdbc</artifactId>
        </dependency>
        
        <!-- OAuth2 Client (for token management) -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security-oauth2-client</artifactId>
        </dependency>
        
        <!-- LDAP -->
        <dependency>
            <groupId>org.springframework.ldap</groupId>
            <artifactId>spring-ldap-core</artifactId>
        </dependency>
        
        <!-- SOAP -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web-services</artifactId>
        </dependency>
        
        <!-- Testing -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        
        <dependency>
            <groupId>org.assertj</groupId>
            <artifactId>assertj-core</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

---

## Development Guidelines

### 1. Keep It Simple
- Use Spring's built-in features
- Don't create custom wrappers
- Raw SQL for database validation
- Tests orchestrate their own flows

### 2. Focus on Integration Tests
- 90% integration tests
- Test against real systems
- No mocking in integration tests

### 3. Test Clarity
- Each test shows complete workflow
- Step-by-step execution visible
- Easy to debug when tests fail

### 4. Security
- Never hardcode credentials
- Use environment variables
- Store sensitive config securely

---

## Quick Start

### 1. Setup Test Infrastructure

```bash
# Start LDAP
docker run -d -p 389:389 osixia/openldap

# Start PostgreSQL
docker run -d -p 5432:5432 -e POSTGRES_PASSWORD=pass postgres
```

### 2. Run Tests

```bash
# All tests
mvn test

# Specific test
mvn test -Dtest=UserProvisioningTest

# Integration tests only
mvn test -Pintegration
```

### 3. Review Results

```bash
# Generate test report
mvn surefire-report:report

# View at target/site/surefire-report.html
```

---

## Summary

### What Makes This Framework Special

1. **Integration-First**: Real system testing, not mocked
2. **Spring Native**: Uses Spring's excellent built-in features
3. **Test as Orchestrator**: Explicit workflows, no hidden magic
4. **Raw SQL**: Flexible database validation like Validator
5. **Simple Design**: No over-engineering, just what's needed

### Key Decisions

✅ Spring RestClient (not custom wrappers)  
✅ Spring OAuth2 Client (not custom token managers)  
✅ Raw SQL with JdbcTemplate (not JPA)  
✅ Tests orchestrate workflows (no orchestration layer)  
✅ Integration tests focus (not extensive unit tests)  

---

**Version 2.0** - Updated January 2025

This specification reflects architectural best practices and lessons learned. Use it as your guide for building a robust, maintainable IdM testing framework.
