# IdM SOAP Connector

NetIQ Identity Manager SOAP connector for Role and Resource services.

## WSDL-Based Code Generation

This module uses **JAX-WS Maven Plugin** to automatically generate Java classes from NetIQ IdM WSDL files.

### Available WSDLs

1. **`idmprov-role.wsdl`** - Role Service API (role assignments, provisioning)
2. **`idmprov-resource.wsdl`** - Resource Service API (application/resource assignments)

### Generated Code Location

Generated JAX-WS stubs are created during Maven compilation:

```
target/generated-sources/jaxws/
└── com/idm/test/connector/soap/generated/
    ├── role/          # Role Service classes
    │   ├── IRemoteRole.java       # Service port interface
    │   ├── RoleService.java       # Service class
    │   ├── RequestRolesAssignmentRequest.java
    │   ├── RemoveRolesRequest.java
    │   └── ... (100+ generated classes)
    └── resource/      # Resource Service classes
        ├── IRemoteResource.java
        ├── ResourceService.java
        └── ... (100+ generated classes)
```

### How It Works

The `jaxws-maven-plugin` in `pom.xml` automatically:

1. Reads WSDL files from `src/main/resources/`
2. Generates type-safe Java classes
3. Creates service interfaces and implementations
4. Handles all JAXB marshalling/unmarshalling

```xml
<plugin>
    <groupId>com.sun.xml.ws</groupId>
    <artifactId>jaxws-maven-plugin</artifactId>
    <version>4.0.2</version>
    <executions>
        <execution>
            <id>generate-role-service</id>
            <goals>
                <goal>wsimport</goal>
            </goals>
            <configuration>
                <wsdlFiles>
                    <wsdlFile>idmprov-role.wsdl</wsdlFile>
                </wsdlFiles>
                <packageName>com.idm.test.connector.soap.generated.role</packageName>
            </configuration>
        </execution>
    </executions>
</plugin>
```

## Adding New SOAP Services

To test a **new application's SOAP service**:

### Step 1: Add WSDL File

Place the WSDL in `src/main/resources/`:

```
src/main/resources/
├── idmprov-role.wsdl
├── idmprov-resource.wsdl
└── my-new-app.wsdl          # New WSDL file
```

### Step 2: Update pom.xml

Add a new execution block:

```xml
<execution>
    <id>generate-myapp-service</id>
    <goals>
        <goal>wsimport</goal>
    </goals>
    <configuration>
        <wsdlFiles>
            <wsdlFile>my-new-app.wsdl</wsdlFile>
        </wsdlFiles>
        <packageName>com.idm.test.connector.soap.generated.myapp</packageName>
    </configuration>
</execution>
```

### Step 3: Rebuild

```bash
mvn clean compile
```

Generated classes appear in:
```
target/generated-sources/jaxws/com/idm/test/connector/soap/generated/myapp/
```

### Step 4: Create Connector

```java
@Component
public class MyAppConnector {

    private final MyAppServicePort servicePort;

    public MyAppConnector(SoapProperties properties) {
        MyAppService service = new MyAppService();
        this.servicePort = service.getMyAppServicePort();

        // Configure endpoint
        BindingProvider bp = (BindingProvider) servicePort;
        bp.getRequestContext().put(
            BindingProvider.ENDPOINT_ADDRESS_PROPERTY,
            properties.getUrl()
        );
    }

    public OperationResult<MyResponse> callOperation(MyRequest request) {
        try {
            MyResponse response = servicePort.myOperation(request);
            return OperationResult.success(response);
        } catch (Exception e) {
            return OperationResult.failure(e.getMessage());
        }
    }
}
```

## Key Benefits

✅ **Type-Safe**: Compile-time checking against WSDL contract
✅ **Maintainable**: Regenerate when WSDL updates
✅ **Scalable**: Add unlimited SOAP services without manual coding
✅ **Standards-Compliant**: JAX-WS industry standard
✅ **No Manual Stubs**: All marshalling handled automatically

## NetIQ IdM WSDL Structures

NetIQ WSDL services use **complex nested structures** with JAXBElements. Example:

### Role Assignment

```java
// Build inner request
RoleAssignmentRequest innerRequest = new RoleAssignmentRequest();
innerRequest.setIdentity("cn=user,ou=people,dc=company,dc=com");
innerRequest.setActionType(RoleAssignmentActionType.GRANT);
innerRequest.setAssignmentType(RoleAssignmentType.USER_TO_ROLE);

// Set roles
DNStringArray roleArray = new DNStringArray();
DNString role = new DNString();
role.setDn("cn=APP_ACCESS,ou=roles,dc=company,dc=com");
roleArray.getDnstring().add(role);
innerRequest.setRoles(roleArray);

// Wrap in outer request with JAXBElement
ObjectFactory factory = new ObjectFactory();
RequestRolesAssignmentRequest outerRequest = new RequestRolesAssignmentRequest();
outerRequest.setAssignRequest(
    factory.createRequestRolesAssignmentRequestAssignRequest(innerRequest)
);

// Call service
RequestRolesAssignmentResponse response =
    roleServicePort.requestRolesAssignment(outerRequest);
```

## Connector Pattern

The `RoleServiceConnector` class wraps the generated stubs and provides:

1. **Simplified API** - Hide WSDL complexity
2. **OperationResult Pattern** - Consistent error handling
3. **Logging** - Request/response timing and debugging
4. **Error Type Classification** - Network, timeout, auth errors

## Configuration

Configure SOAP endpoints in `application.yml`:

```yaml
idm:
  test:
    soap:
      url: http://idm.company.com:8080/IDMProv/role/service
      username: admin
      password: ${SOAP_PASSWORD}
      connection-timeout: 10000
      read-timeout: 30000
```

## Testing

Tests use **WireMock** to mock SOAP responses without hitting real services.

See `RoleServiceConnectorTest.java` for examples.

## Maven Commands

```bash
# Generate WSDL classes only
mvn generate-sources

# Full build with tests
mvn clean install

# View generated sources
ls target/generated-sources/jaxws/com/idm/test/connector/soap/generated/
```

## Documentation

- **JAX-WS**: https://jakarta.ee/specifications/xml-web-services/
- **NetIQ IdM SOAP APIs**: Check IdM documentation for WSDL locations
- **Spring Boot SOAP**: https://spring.io/guides/gs/producing-web-service/
