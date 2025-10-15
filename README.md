# IdM Test Framework

> **Integration Testing Framework for Identity Management Systems**

A comprehensive testing framework for OpenText Identity Manager (IdM) integrations, capable of testing provisioning workflows across LDAP, Databases, REST APIs, and SOAP services.

## 🏗️ Architecture

This is a **multi-module Maven project** with a clean separation of concerns:

```
idm-test-framework/
├── idm-test-core/              # Core testing engine and utilities
├── idm-test-utils/             # Test data generators and custom assertions
├── idm-connector-ldap/         # LDAP/eDirectory connector
├── idm-connector-database/     # Multi-database JDBC connector (raw SQL)
├── idm-connector-rest/         # REST API connector
├── idm-connector-soap/         # SOAP connector for Role Service
├── idm-connector-idm/          # IdM REST/SOAP combined connector
└── idm-test-scenarios/         # Integration test implementations
```

## 🚀 Quick Start

### Prerequisites

- **Java 21** (LTS)
- **Maven 3.9+**
- **Docker** (optional, for test infrastructure)

### Build the Project

```bash
# Build all modules
./mvnw clean install

# Skip tests during build
./mvnw clean install -DskipTests

# Build specific module
./mvnw clean install -pl idm-test-core
```

### Run Tests

```bash
# Run all tests
./mvnw test

# Run specific test class
./mvnw test -Dtest=UserProvisioningTest

# Run tests in specific module
./mvnw test -pl idm-test-scenarios
```

## 📦 Modules

### idm-test-core
**Purpose:** Core testing engine with foundational utilities

**Key Components:**
- `OperationResult<T>` - Result wrapper for all connector operations
- `TestExecutor` - Wait/retry utilities for async provisioning
- Custom exceptions hierarchy
- Logging infrastructure

**Dependencies:**
- Spring Boot Starter
- Apache Commons Lang3

---

### idm-test-utils
**Purpose:** Shared utilities for test data generation and assertions

**Key Components:**
- Test data generators (users, roles, attributes)
- Custom AssertJ assertions
- Fluent builders for test objects

**Dependencies:**
- idm-test-core
- AssertJ

---

### idm-connector-ldap
**Purpose:** LDAP/eDirectory operations

**Key Components:**
- `EdirectoryConnector` - CRUD operations for LDAP
- Attribute verification utilities
- DN management

**Dependencies:**
- idm-test-core
- Spring LDAP
- UnboundID LDAP SDK
- Apache Directory Server (test only)

---

### idm-connector-database
**Purpose:** Multi-database support with raw SQL

**Key Components:**
- `MultiDatabaseConnector` - Execute queries on any configured database
- Dynamic JdbcTemplate management
- Connection pooling (HikariCP)

**Dependencies:**
- idm-test-core
- Spring JDBC
- Database drivers (optional)
- H2, PostgreSQL (test only)

---

### idm-connector-rest
**Purpose:** REST API support using Spring's RestClient

**Key Components:**
- RestClient configuration
- OAuth2 support (Spring Security OAuth2 Client)
- Error handling and mapping

**Dependencies:**
- idm-test-core
- Spring Web
- Spring Security OAuth2 Client
- WireMock (test only)

---

### idm-connector-soap
**Purpose:** SOAP service connector

**Key Components:**
- `RoleServiceConnector` - NetIQ Role Service client
- WebServiceTemplate configuration
- JAXB marshalling/unmarshalling

**Dependencies:**
- idm-test-core
- Spring Web Services
- JAXB

---

### idm-connector-idm
**Purpose:** Combined IdM connector (REST + SOAP)

**Key Components:**
- IdM-specific REST operations
- Role Service SOAP operations
- Unified IdM interface

**Dependencies:**
- idm-connector-rest
- idm-connector-soap

---

### idm-test-scenarios
**Purpose:** Integration test implementations

**Test Categories:**
- **provisioning/** - User creation and provisioning tests
- **modification/** - Update and change tests
- **deprovisioning/** - Termination and cleanup tests
- **integration/** - App-specific workflow tests

**Dependencies:**
- All connectors
- idm-test-utils
- Testcontainers

## 🎯 Design Principles

### ✅ What We DO

1. **Use Spring's Built-in Clients**
   - `RestClient` (Spring 6.1+) for REST APIs
   - `OAuth2AuthorizedClientManager` for OAuth2
   - `JdbcTemplate` for database queries
   - `WebServiceTemplate` for SOAP

2. **Integration-First Testing**
   - 90% integration tests against real systems
   - 10% unit tests for utilities only
   - No mocking of external systems in integration tests

3. **Tests as Orchestrators**
   - Each test explicitly defines its workflow
   - Step-by-step execution visible in test code
   - No hidden orchestration layers

4. **Raw SQL for Database Validation**
   - Execute custom SELECT statements
   - Results as `List<Map<String, Object>>`
   - Support multiple databases dynamically
   - **NO JPA/Hibernate**

### ❌ What We DON'T DO

- ❌ Custom REST client wrappers
- ❌ Custom OAuth token managers
- ❌ JPA entities for database validation
- ❌ Orchestration layers hiding workflow logic
- ❌ Extensive unit testing of integration code

## 🛠️ Technology Stack

| Component | Technology | Version |
|-----------|-----------|---------|
| Java | OpenJDK | 21 (LTS) |
| Framework | Spring Boot | 3.5.6 |
| Build Tool | Maven | 3.9.x |
| Testing | JUnit 5 | 5.11.x |
| Assertions | AssertJ | 3.26.x |
| LDAP | Spring LDAP | 3.2.9 |
| LDAP SDK | UnboundID | 7.0.1 |
| Containers | Testcontainers | 1.20.4 |
| Mocking | WireMock | 3.10.0 |

## 📖 Documentation

- **[SPECIFICATION.md](SPECIFICATION.md)** - Complete architectural specification
- **[CLAUDE.md](CLAUDE.md)** - Development guide for Claude Code

## 🏃 Development Workflow

### 1. Start Test Infrastructure (Optional)

```bash
# Start LDAP
docker run -d -p 389:389 osixia/openldap

# Start PostgreSQL
docker run -d -p 5432:5432 -e POSTGRES_PASSWORD=pass postgres
```

### 2. Configure Connections

Create `idm-test-scenarios/src/test/resources/application-local.yml`:

```yaml
idm:
  test:
    ldap:
      url: ldap://localhost:389
      base-dn: dc=company,dc=com
    databases:
      app1:
        jdbc-url: jdbc:postgresql://localhost:5432/app1
        username: user
        password: pass
```

### 3. Run Tests

```bash
# Run with local profile
./mvnw test -Dspring.profiles.active=local
```

## 🔍 Example Test

```java
@SpringBootTest
class UserProvisioningTest {

    @Autowired
    private IdmRestConnector idmConnector;

    @Autowired
    private EdirectoryConnector ldapConnector;

    @Autowired
    private TestExecutor testExecutor;

    @Test
    @DisplayName("Complete workflow: Create → Verify")
    void testUserProvisioning() {
        String userId = "test.user." + System.currentTimeMillis();

        // STEP 1: Create user in IdM
        CreateUserRequest request = new CreateUserRequest(userId);
        OperationResult<CreateUserResponse> result =
            idmConnector.post("/users", request, CreateUserResponse.class);

        assertThat(result.isSuccess()).isTrue();

        // STEP 2: Wait and verify in eDirectory
        boolean exists = testExecutor.waitUntil(
            () -> ldapConnector.read("cn=" + userId + ",ou=users").isSuccess(),
            Duration.ofSeconds(30),
            Duration.ofSeconds(2)
        );

        assertThat(exists).isTrue();
    }
}
```

## 📝 Current Status

### Phase 1: ✅ COMPLETED
- ✅ Multi-module Maven structure
- ✅ All module POMs created
- ✅ Dependency management configured
- ✅ Build verified (BUILD SUCCESS)

### Next Phases
- Phase 2: Core Module Implementation
- Phase 3: Utils Module Implementation
- Phase 4: Database Connector
- Phase 5: LDAP Connector
- Phase 6: REST Connector
- Phase 7: SOAP Connector
- Phase 8: Configuration Infrastructure
- Phase 9: Test Scenarios
- Phase 10: Docker & CI/CD

## 🤝 Contributing

See [SPECIFICATION.md](SPECIFICATION.md) for architecture details and design decisions.

## 📄 License

Internal project - OpenText Identity Management

---

**Version:** 1.0.0-SNAPSHOT
**Last Updated:** October 2025
