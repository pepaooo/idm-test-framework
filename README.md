# IdM Test Framework

> **Modern Integration Testing Framework for Identity Management Systems**

[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.6-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Testcontainers](https://img.shields.io/badge/Testcontainers-1.20.4-blue.svg)](https://www.testcontainers.org/)
[![Tests](https://img.shields.io/badge/tests-27%20passing-brightgreen.svg)]()

A comprehensive integration testing framework for Identity Management (IdM) systems built on Spring Boot, JUnit 5, and Testcontainers. Test provisioning workflows across LDAP, databases, REST APIs, and SOAP services with **zero manual setup**.

---

## ✨ Features

- 🐳 **Zero Setup Testing** - Testcontainers automatically manages LDAP and SOAP services
- 🔌 **Multi-System Integration** - Test workflows across LDAP, databases, REST, and SOAP
- 🎯 **20+ Test Scenarios** - Provisioning, modification, deprovisioning, and role assignment
- 🚀 **Spring Boot 3.5.6** - Modern Spring Framework with Java 21
- 🧪 **JUnit 5 & AssertJ** - Modern testing framework with fluent assertions
- 📊 **Automatic Cleanup** - Test data tracked and cleaned up after each test
- 🎭 **WireMock SOAP Mocking** - Mock SOAP services without real IdM infrastructure
- 🐘 **Docker Compose** - Local development environment with one command
- ⚙️ **GitHub Actions CI/CD** - Automated testing on every commit

---

## 🚀 Quick Start

### Prerequisites

- **Java 21** or later
- **Maven 3.9+**
- **Docker Desktop** (for Testcontainers and Docker Compose)

### Run Tests (Automatic Infrastructure)

```bash
# Build the project
mvn clean install -DskipTests

# Run all tests (Testcontainers starts LDAP & SOAP automatically)
cd idm-test-scenarios
mvn test

# Run configuration tests only (no Docker needed)
mvn test -Dtest=ConfigurationTest
```

**Result**: ✅ Tests run with automatic LDAP and SOAP service management!

### Using Docker Compose (Manual Development)

```bash
# Start all services (LDAP, phpLDAPadmin, WireMock, PostgreSQL)
docker-compose up -d

# Access phpLDAPadmin Web UI
open http://localhost:8080

# Run tests against running services
mvn test

# Stop services
docker-compose down
```

---

## 📁 Project Structure

```
idm-test-framework/
├── idm-test-core/              # Test execution engine (TestExecutor, OperationResult)
├── idm-test-utils/             # Test data generators and utilities
├── idm-connector-ldap/         # eDirectory/LDAP connector
├── idm-connector-database/     # Multi-database JDBC connector
├── idm-connector-rest/         # REST API connectors
├── idm-connector-soap/         # SOAP/Role Service connector
├── idm-connector-idm/          # NetIQ IdM connector
├── idm-test-scenarios/         # Integration test scenarios
│   └── src/test/
│       ├── java/
│       │   ├── base/           # BaseIntegrationTest with Testcontainers
│       │   ├── config/         # ConfigurationTest
│       │   ├── provisioning/   # User provisioning tests
│       │   ├── modification/   # User modification tests
│       │   ├── deprovisioning/ # User deprovisioning tests
│       │   └── integration/    # Multi-system integration tests
│       └── resources/
│           ├── wiremock/       # SOAP stubs for WireMock
│           ├── schema.sql      # H2 database schema
│           └── application.yml # Test configuration
├── docker-compose.yml          # Local development environment
├── .github/workflows/ci.yml    # GitHub Actions CI/CD pipeline
└── README.md                   # This file
```

---

## 🧪 Test Scenarios

### Provisioning (4 tests)
- Create user in LDAP and verify provisioning
- Create user in database and verify
- Handle provisioning timeouts
- Provision with minimal attributes

### Role Assignment (4 tests)
- Assign role and verify LDAP group membership
- Assign role with database access
- Assign multiple roles
- Handle assignment failures

### Modification (4 tests)
- Modify LDAP attributes and sync
- Modify database and sync
- Email change across all systems
- Attribute removal

### Deprovisioning (5 tests)
- Soft delete (disable user account)
- Hard delete (remove from all systems)
- Access revocation workflow
- Grace period handling
- Related data cleanup

### Multi-System Integration (3 tests)
- New hire onboarding (9-step workflow)
- Employee termination workflow
- Department transfer workflow

### Configuration (7 tests)
- LDAP configuration validation
- Database configuration validation (4 DBs)
- REST API configuration validation (4 APIs)
- SOAP configuration validation
- TestExecutor configuration validation

---

## 🏗️ Architecture

### Core Principles

✅ **Spring's Built-in Clients** - RestClient, RestTemplate, WebClient (no custom wrappers)
✅ **Raw SQL with JdbcTemplate** - For database validation (no JPA/Hibernate)
✅ **Testcontainers** - Automatic service management (LDAP, databases, mocks)
✅ **WireMock** - SOAP service mocking (no real IdM server needed)
✅ **Integration-First** - 90% integration tests, 10% unit tests

### Key Components

#### TestExecutor
Provides async operation support:
```java
boolean success = testExecutor.waitUntil(
    () -> connector.exists(userId),
    Duration.ofMinutes(5),
    Duration.ofSeconds(5)
);
```

#### Connectors
Simple, focused connectors for each system:
- **EdirectoryConnector** - LDAP CRUD operations
- **MultiDatabaseConnector** - Execute SQL on any configured database
- **RoleServiceConnector** - SOAP client for NetIQ Role Service
- **RestConnectors** - Thin wrappers around Spring's RestClient

#### BaseIntegrationTest
Provides automatic infrastructure and cleanup:
```java
@SpringBootTest
@Testcontainers
public abstract class BaseIntegrationTest {

    @Container
    protected static final GenericContainer<?> LDAP_CONTAINER = ...;

    protected static final WireMockServer WIREMOCK_SERVER = ...;

    @AfterEach
    void tearDown() {
        cleanupLdap();      // Automatic cleanup
        cleanupDatabase();  // Automatic cleanup
        cleanupRoles();     // Automatic cleanup
    }
}
```

---

## 🧰 Maven Commands

```bash
# Build without tests
mvn clean install -DskipTests

# Run all tests
mvn test

# Run specific test class
mvn test -Dtest=UserProvisioningWorkflowTest

# Run specific test method
mvn test -Dtest=UserDeprovisioningWorkflowTest#testUserDisableWorkflow

# Run with debug logging
mvn test -Dtest=ConfigurationTest -X
```

---

## 🐳 Docker Services

### OpenLDAP (port 10389)
- Base DN: `dc=test,dc=com`
- Admin DN: `cn=admin,dc=test,dc=com`
- Password: `test`

### phpLDAPadmin (port 8080)
- Web UI for LDAP management
- Login: `cn=admin,dc=test,dc=com` / `test`
- URL: http://localhost:8080

### WireMock (port 8090)
- SOAP service mocking
- Stubs in `src/test/resources/wiremock/mappings/`
- Admin UI: http://localhost:8090/__admin/

### PostgreSQL (port 5432) - Optional
- Database: `idmtest`
- User: `idmtest`
- Password: `idmtest`

---

## 📚 Documentation

Documentation is organised by how much you should trust it.

**Reference** — maintained, describes the framework as it is:

- **[Specification](docs/reference/specification.md)** - Complete architecture specification
- **[Configuration](docs/reference/configuration.md)** - How to configure connectors and databases
- **[Running tests](docs/reference/running-tests.md)** - Comprehensive test execution guide
- **[CLAUDE.md](CLAUDE.md)** - Development guidelines for Claude Code

**Specs and plans** — design documents for in-flight work:

- **[Green test baseline](docs/specs/2026-09-11-suite-green-baseline-design.md)** - Design for the current state of the suite
- **[Implementation plan](docs/plans/2026-09-11-green-test-baseline.md)** - Task breakdown for that design

**History** — frozen session reports, kept for the record only:

- **[docs/history/](docs/history/README.md)** - Phase 9 and 10 reports. Several are
  inaccurate; the banner in that folder says which. Do not use them to judge current state.

---

## 🛠️ Technology Stack

| Component | Technology | Version |
|-----------|-----------|---------|
| Java | OpenJDK | 21 (LTS) |
| Framework | Spring Boot | 3.5.6 |
| Build Tool | Maven | 3.9.x |
| Testing | JUnit 5 | 5.11.x |
| Assertions | AssertJ | 3.26.x |
| LDAP | Spring LDAP | 3.2.9 |
| Containers | Testcontainers | 1.20.4 |
| Mocking | WireMock | 3.10.0 |

---

## 📝 Project Status

### ✅ Phase 1-10: COMPLETED

| Phase | Description | Status |
|-------|-------------|--------|
| Phase 1 | Project structure and Maven setup | ✅ Complete |
| Phase 2 | Core module (OperationResult, TestExecutor) | ✅ Complete |
| Phase 3 | Utils module (TestDataGenerator, Assertions) | ✅ Complete |
| Phase 4 | Multi-database connector | ✅ Complete |
| Phase 5 | LDAP connector | ✅ Complete |
| Phase 6 | REST connector | ✅ Complete |
| Phase 7 | SOAP connector | ✅ Complete |
| Phase 8 | Configuration infrastructure | ✅ Complete |
| Phase 9 | Integration test scenarios (20+ tests) | ✅ Complete |
| Phase 10 | Docker & CI/CD automation | ⚠️ Superseded — see [green baseline spec](docs/specs/2026-09-11-suite-green-baseline-design.md) |

### Build Status

Last verified: 2026-09-11 via `sh ./mvnw clean test`

- All 9 modules compile and test successfully
- Total: 167 tests, 0 failures, 0 errors, 0 skipped
- No Docker required — the suite runs on an embedded UnboundID LDAP server
- WireMock provides SOAP stubs on a dynamically allocated port
- Docker Compose and the CI workflow remain available for phase (b),
  when the framework is pointed at a real IdM environment

---

## 🤝 Contributing

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

---

## 🎯 Roadmap (Future Enhancements)

### Phase 11: Enhanced Observability
- [ ] Test coverage reporting (JaCoCo)
- [ ] Performance metrics
- [ ] Test execution dashboards
- [ ] Allure test reports

### Phase 12: Extended Test Scenarios
- [ ] REST API integration tests
- [ ] Multi-tenant test scenarios
- [ ] Load/performance tests
- [ ] Security/penetration tests

---

## 🙏 Acknowledgments

- **Spring Boot** - Application framework
- **Testcontainers** - Docker-based integration testing
- **WireMock** - HTTP/SOAP service mocking
- **AssertJ** - Fluent assertions
- **OpenLDAP** - LDAP directory server

---

**Built with ❤️ for Identity Management Testing**

*Version: 1.0.0-SNAPSHOT | Last Updated: 2025-10-17*
