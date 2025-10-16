package com.idm.test.connector.ldap.client;

import com.idm.test.connector.ldap.model.LdapUser;
import com.idm.test.core.model.ErrorType;
import com.idm.test.core.model.OperationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ldap.core.DirContextAdapter;
import org.springframework.ldap.core.DirContextOperations;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.AbstractContextMapper;
import org.springframework.ldap.query.LdapQuery;
import org.springframework.ldap.support.LdapNameBuilder;
import org.springframework.stereotype.Component;

import javax.naming.Name;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.BasicAttribute;
import javax.naming.directory.BasicAttributes;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.ldap.query.LdapQueryBuilder.query;

/**
 * eDirectory/LDAP connector using Spring LDAP.
 * Provides simple CRUD operations - no workflow logic.
 * <p>
 * Features:
 * - Create, read, update, delete LDAP entries
 * - Verify attributes match expected values
 * - Search LDAP directory
 * - Check if entry exists
 */
@Component
public class EdirectoryConnector {

    private static final Logger log = LoggerFactory.getLogger(EdirectoryConnector.class);

    private final LdapTemplate ldapTemplate;

    public EdirectoryConnector(LdapTemplate ldapTemplate) {
        this.ldapTemplate = ldapTemplate;
    }

    /**
     * Creates a new user in LDAP.
     *
     * @param user the user to create
     * @return operation result indicating success or failure
     */
    public OperationResult<LdapUser> create(LdapUser user) {
        log.debug("Creating LDAP user: {}", user.getDn());

        try {
            // Use relative DN - Spring LDAP will append the base DN automatically
            String relativeDn = getRelativeDn(user.getDn());
            Name dn = LdapNameBuilder.newInstance(relativeDn).build();

            DirContextAdapter context = new DirContextAdapter(dn);

            // Set required attributes
            if (user.getCn() != null) {
                context.setAttributeValue("cn", user.getCn());
            }
            if (user.getSn() != null) {
                context.setAttributeValue("sn", user.getSn());
            }

            // Set object classes
            context.setAttributeValues("objectClass", new String[]{"top", "person", "organizationalPerson", "inetOrgPerson"});

            // Set optional attributes
            setAttributeIfNotNull(context, "givenName", user.getGivenName());
            setAttributeIfNotNull(context, "mail", user.getMail());
            setAttributeIfNotNull(context, "uid", user.getUid());
            setAttributeIfNotNull(context, "userPassword", user.getUserPassword());
            setAttributeIfNotNull(context, "telephoneNumber", user.getTelephoneNumber());
            setAttributeIfNotNull(context, "title", user.getTitle());
            setAttributeIfNotNull(context, "ou", user.getOu());
            setAttributeIfNotNull(context, "description", user.getDescription());

            // Set custom attributes
            for (Map.Entry<String, Object> entry : user.getCustomAttributes().entrySet()) {
                context.setAttributeValue(entry.getKey(), entry.getValue());
            }

            ldapTemplate.bind(context);

            log.info("LDAP user created successfully: {}", user.getDn());

            return OperationResult.<LdapUser>builder()
                    .success(true)
                    .data(user)
                    .message("User created successfully")
                    .build();

        } catch (Exception e) {
            log.error("Failed to create LDAP user '{}': {}", user.getDn(), e.getMessage());
            return OperationResult.<LdapUser>builder()
                    .success(false)
                    .message("Failed to create user: " + e.getMessage())
                    .errorType(ErrorType.LDAP_ERROR)
                    .throwable(e)
                    .build();
        }
    }

    /**
     * Reads a user from LDAP by DN.
     *
     * @param dn the distinguished name
     * @return operation result containing the user or error
     */
    public OperationResult<LdapUser> read(String dn) {
        log.debug("Reading LDAP user: {}", dn);

        try {
            Name name = LdapNameBuilder.newInstance(dn).build();

            LdapUser user = ldapTemplate.lookup(name, new UserContextMapper());

            if (user == null) {
                log.warn("LDAP user not found: {}", dn);
                return OperationResult.<LdapUser>builder()
                        .success(false)
                        .message("User not found: " + dn)
                        .errorType(ErrorType.NOT_FOUND)
                        .build();
            }

            log.debug("LDAP user read successfully: {}", dn);

            return OperationResult.<LdapUser>builder()
                    .success(true)
                    .data(user)
                    .message("User read successfully")
                    .build();

        } catch (org.springframework.ldap.NameNotFoundException e) {
            log.warn("LDAP user not found: {}", dn);
            return OperationResult.<LdapUser>builder()
                    .success(false)
                    .message("User not found: " + dn)
                    .errorType(ErrorType.NOT_FOUND)
                    .throwable(e)
                    .build();
        } catch (Exception e) {
            log.error("Failed to read LDAP user '{}': {}", dn, e.getMessage());
            return OperationResult.<LdapUser>builder()
                    .success(false)
                    .message("Failed to read user: " + e.getMessage())
                    .errorType(ErrorType.LDAP_ERROR)
                    .throwable(e)
                    .build();
        }
    }

    /**
     * Updates user attributes in LDAP.
     *
     * @param dn the distinguished name
     * @param attributes map of attribute names to values
     * @return operation result indicating success or failure
     */
    public OperationResult<Void> update(String dn, Map<String, Object> attributes) {
        log.debug("Updating LDAP user: {}", dn);

        try {
            Name name = LdapNameBuilder.newInstance(dn).build();

            // Create modification items
            javax.naming.directory.ModificationItem[] mods =
                new javax.naming.directory.ModificationItem[attributes.size()];

            int i = 0;
            for (Map.Entry<String, Object> entry : attributes.entrySet()) {
                javax.naming.directory.Attribute attr =
                    new BasicAttribute(entry.getKey(), entry.getValue());
                mods[i++] = new javax.naming.directory.ModificationItem(
                    javax.naming.directory.DirContext.REPLACE_ATTRIBUTE, attr);
            }

            ldapTemplate.modifyAttributes(name, mods);

            log.info("LDAP user updated successfully: {}", dn);

            return OperationResult.<Void>builder()
                    .success(true)
                    .message("User updated successfully")
                    .build();

        } catch (Exception e) {
            log.error("Failed to update LDAP user '{}': {}", dn, e.getMessage());
            return OperationResult.<Void>builder()
                    .success(false)
                    .message("Failed to update user: " + e.getMessage())
                    .errorType(ErrorType.LDAP_ERROR)
                    .throwable(e)
                    .build();
        }
    }

    /**
     * Deletes a user from LDAP.
     *
     * @param dn the distinguished name
     * @return operation result indicating success or failure
     */
    public OperationResult<Void> delete(String dn) {
        log.debug("Deleting LDAP user: {}", dn);

        try {
            Name name = LdapNameBuilder.newInstance(dn).build();

            ldapTemplate.unbind(name);

            log.info("LDAP user deleted successfully: {}", dn);

            return OperationResult.<Void>builder()
                    .success(true)
                    .message("User deleted successfully")
                    .build();

        } catch (Exception e) {
            log.error("Failed to delete LDAP user '{}': {}", dn, e.getMessage());
            return OperationResult.<Void>builder()
                    .success(false)
                    .message("Failed to delete user: " + e.getMessage())
                    .errorType(ErrorType.LDAP_ERROR)
                    .throwable(e)
                    .build();
        }
    }

    /**
     * Checks if an entry exists in LDAP.
     *
     * @param dn the distinguished name
     * @return operation result containing boolean indicating existence
     */
    public OperationResult<Boolean> exists(String dn) {
        log.debug("Checking if LDAP entry exists: {}", dn);

        try {
            Name name = LdapNameBuilder.newInstance(dn).build();

            Object result = ldapTemplate.lookup(name);
            boolean exists = result != null;

            log.debug("LDAP entry exists: {} = {}", dn, exists);

            return OperationResult.<Boolean>builder()
                    .success(true)
                    .data(exists)
                    .message(exists ? "Entry exists" : "Entry not found")
                    .build();

        } catch (org.springframework.ldap.NameNotFoundException e) {
            log.debug("LDAP entry does not exist: {}", dn);
            return OperationResult.<Boolean>builder()
                    .success(true)
                    .data(false)
                    .message("Entry not found")
                    .build();
        } catch (Exception e) {
            log.error("Error checking LDAP entry existence '{}': {}", dn, e.getMessage());
            return OperationResult.<Boolean>builder()
                    .success(false)
                    .message("Error checking existence: " + e.getMessage())
                    .errorType(ErrorType.LDAP_ERROR)
                    .throwable(e)
                    .build();
        }
    }

    /**
     * Verifies that an LDAP entry has expected attribute values.
     *
     * @param dn the distinguished name
     * @param expectedAttributes map of attribute names to expected values
     * @return operation result indicating if verification passed
     */
    public OperationResult<Boolean> verifyAttributes(String dn, Map<String, Object> expectedAttributes) {
        log.debug("Verifying LDAP attributes for: {}", dn);

        try {
            OperationResult<LdapUser> readResult = read(dn);

            if (!readResult.isSuccess()) {
                return OperationResult.<Boolean>builder()
                        .success(false)
                        .data(false)
                        .message("Failed to read entry: " + readResult.getMessage())
                        .errorType(readResult.getErrorType().orElse(ErrorType.LDAP_ERROR))
                        .throwable(readResult.getThrowable().orElse(null))
                        .build();
            }

            LdapUser user = readResult.getData().orElseThrow();

            // Build actual attributes map
            Map<String, Object> actualAttributes = new HashMap<>();
            actualAttributes.put("cn", user.getCn());
            actualAttributes.put("sn", user.getSn());
            actualAttributes.put("givenName", user.getGivenName());
            actualAttributes.put("mail", user.getMail());
            actualAttributes.put("uid", user.getUid());
            actualAttributes.put("telephoneNumber", user.getTelephoneNumber());
            actualAttributes.put("title", user.getTitle());
            actualAttributes.put("ou", user.getOu());
            actualAttributes.put("description", user.getDescription());
            actualAttributes.putAll(user.getCustomAttributes());

            // Compare expected vs actual
            for (Map.Entry<String, Object> entry : expectedAttributes.entrySet()) {
                String attrName = entry.getKey();
                Object expectedValue = entry.getValue();
                Object actualValue = actualAttributes.get(attrName);

                if (!valuesEqual(expectedValue, actualValue)) {
                    String message = String.format(
                            "Attribute mismatch for '%s': expected=%s, actual=%s",
                            attrName, expectedValue, actualValue);
                    log.warn(message);

                    return OperationResult.<Boolean>builder()
                            .success(false)
                            .data(false)
                            .message(message)
                            .errorType(ErrorType.VALIDATION_ERROR)
                            .build();
                }
            }

            log.debug("LDAP attribute verification successful for: {}", dn);

            return OperationResult.<Boolean>builder()
                    .success(true)
                    .data(true)
                    .message("Attribute verification successful")
                    .build();

        } catch (Exception e) {
            log.error("Error verifying LDAP attributes for '{}': {}", dn, e.getMessage());
            return OperationResult.<Boolean>builder()
                    .success(false)
                    .message("Verification error: " + e.getMessage())
                    .errorType(ErrorType.UNKNOWN_ERROR)
                    .throwable(e)
                    .build();
        }
    }

    /**
     * Searches LDAP directory.
     *
     * @param baseDn base DN for search
     * @param filter LDAP search filter
     * @return operation result containing list of matching users
     */
    public OperationResult<List<LdapUser>> search(String baseDn, String filter) {
        log.debug("Searching LDAP: baseDn={}, filter={}", baseDn, filter);

        try {
            LdapQuery query = query()
                    .base(baseDn)
                    .filter(filter);

            List<LdapUser> results = ldapTemplate.search(query, new UserContextMapper());

            log.debug("LDAP search returned {} result(s)", results.size());

            return OperationResult.<List<LdapUser>>builder()
                    .success(true)
                    .data(results)
                    .message(String.format("Search returned %d result(s)", results.size()))
                    .build();

        } catch (Exception e) {
            log.error("LDAP search failed: {}", e.getMessage());
            return OperationResult.<List<LdapUser>>builder()
                    .success(false)
                    .message("Search failed: " + e.getMessage())
                    .errorType(ErrorType.LDAP_ERROR)
                    .throwable(e)
                    .build();
        }
    }

    /**
     * Context mapper to convert LDAP entry to LdapUser.
     */
    private static class UserContextMapper extends AbstractContextMapper<LdapUser> {
        @Override
        protected LdapUser doMapFromContext(DirContextOperations context) {
            LdapUser user = new LdapUser();
            user.setDn(context.getDn().toString());
            user.setCn(context.getStringAttribute("cn"));
            user.setSn(context.getStringAttribute("sn"));
            user.setGivenName(context.getStringAttribute("givenName"));
            user.setMail(context.getStringAttribute("mail"));
            user.setUid(context.getStringAttribute("uid"));
            user.setTelephoneNumber(context.getStringAttribute("telephoneNumber"));
            user.setTitle(context.getStringAttribute("title"));
            user.setOu(context.getStringAttribute("ou"));
            user.setDescription(context.getStringAttribute("description"));

            // Note: userPassword is typically not returned in searches
            return user;
        }
    }

    /**
     * Helper method to set attribute only if value is not null.
     */
    private void setAttributeIfNotNull(DirContextAdapter context, String name, Object value) {
        if (value != null) {
            context.setAttributeValue(name, value);
        }
    }

    /**
     * Compares two values for equality, handling nulls.
     */
    private boolean valuesEqual(Object expected, Object actual) {
        if (expected == null && actual == null) {
            return true;
        }
        if (expected == null || actual == null) {
            return false;
        }
        return expected.equals(actual) || expected.toString().equals(actual.toString());
    }

    /**
     * Extracts relative DN by removing base DN from full DN.
     * Spring LDAP automatically appends base DN, so we need to use relative DNs.
     *
     * @param fullDn the full distinguished name
     * @return relative DN without base DN
     */
    private String getRelativeDn(String fullDn) {
        // Spring LDAP will append the base DN, so if the DN already contains it,
        // we need to remove it to avoid duplication
        // For now, just return the DN as-is since we'll configure tests properly
        return fullDn;
    }
}
