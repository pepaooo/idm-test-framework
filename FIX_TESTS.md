# Test API Fixes - Quick Reference

## API Changes Needed

### 1. EdirectoryConnector.create()
**Before:**
```java
edirectoryConnector.create(userDn, testUser.toLdapAttributes());
```

**After:**
```java
edirectoryConnector.create(testUser.toLdapUser(userDn));
```

### 2. EdirectoryConnector.read()
**Before:**
```java
OperationResult<Attributes> readResult = edirectoryConnector.read(userDn);
Attributes attributes = readResult.getData().get();
String email = attributes.get("mail").get().toString();
```

**After:**
```java
OperationResult<LdapUser> readResult = edirectoryConnector.read(userDn);
LdapUser user = readResult.getData().get();
String email = user.getMail();
```

### 3. EdirectoryConnector.update()
**Before:**
```java
Map<String, String> modifications = new HashMap<>();
```

**After:**
```java
Map<String, Object> modifications = new HashMap<>();
```

### 4. OperationResult error handling
**Before:**
```java
result.getErrorMessage()
```

**After:**
```java
result.getMessage()
```

## Files That Need Fixing

- ✅ UserDeprovisioningWorkflowTest.java (FIXED)
- ⏳ UserProvisioningWorkflowTest.java
- ⏳ RoleAssignmentWorkflowTest.java
- ⏳ UserModificationWorkflowTest.java
- ⏳ MultiSystemIntegrationTest.java

## Automated Fix Command

Run this to fix all occurrences:

```bash
# Fix create() calls
find idm-test-scenarios -name "*Test.java" -exec sed -i 's/edirectoryConnector\.create(userDn, testUser\.toLdapAttributes())/edirectoryConnector.create(testUser.toLdapUser(userDn))/g' {} \;

# Fix Map<String, String> to Map<String, Object>
find idm-test-scenarios -name "*Test.java" -exec sed -i 's/Map<String, String> modifications/Map<String, Object> modifications/g' {} \;
```
