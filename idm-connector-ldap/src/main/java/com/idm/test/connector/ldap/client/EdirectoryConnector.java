package com.idm.test.connector.ldap.client;

import com.idm.test.connector.ldap.model.LdapUser;
import com.idm.test.core.model.ErrorType;
import com.idm.test.core.model.OperationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ldap.core.AttributesMapper;
import org.springframework.ldap.core.DirContextAdapter;
import org.springframework.ldap.core.DirContextOperations;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.AbstractContextMapper;
import org.springframework.ldap.query.LdapQuery;
import org.springframework.ldap.support.LdapNameBuilder;

import javax.naming.Name;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.BasicAttribute;
import javax.naming.directory.BasicAttributes;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

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
 * <p>
 * Built per directory by {@code LdapConfiguration}; see {@link LdapDirectories}.
 */
public class EdirectoryConnector {

    private static final Logger log = LoggerFactory.getLogger(EdirectoryConnector.class);

    private final LdapTemplate ldapTemplate;

    public EdirectoryConnector(LdapTemplate ldapTemplate) {
        this.ldapTemplate = ldapTemplate;
    }

    private static final String[] DEFAULT_OBJECT_CLASSES = {"top", "person", "organizationalPerson", "inetOrgPerson"};

    /** The object classes to write: the author's (one or a list), else the inetOrgPerson chain. */
    static String[] objectClasses(Object requested) {
        if (requested == null) {
            return DEFAULT_OBJECT_CLASSES.clone();
        }
        List<String> classes = requested instanceof Collection<?> values
                ? values.stream().map(String::valueOf).toList()
                : List.of(String.valueOf(requested));
        boolean hasNull = requested instanceof Collection<?> values && values.stream().anyMatch(Objects::isNull);
        if (hasNull || classes.isEmpty() || classes.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("objectClass must name at least one object class, with no blank values");
        }
        return classes.toArray(String[]::new);
    }

    /** Removes every entry whose name equals {@code name} ignoring case (LDAP names are case-insensitive), returning the last value. */
    private static Object removeIgnoringCase(Map<String, Object> attributes, String name) {
        Object found = null;
        for (Iterator<Map.Entry<String, Object>> it = attributes.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Object> entry = it.next();
            if (entry.getKey().equalsIgnoreCase(name)) {
                found = entry.getValue();
                it.remove();
            }
        }
        return found;
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

            // Object classes: the author's, when the attributes name them, else the inetOrgPerson chain
            Map<String, Object> custom = new LinkedHashMap<>(user.getCustomAttributes());
            context.setAttributeValues("objectClass", objectClasses(removeIgnoringCase(custom, "objectClass")));

            // Set optional attributes
            setAttributeIfNotNull(context, "givenName", user.getGivenName());
            setAttributeIfNotNull(context, "mail", user.getMail());
            setAttributeIfNotNull(context, "uid", user.getUid());
            setAttributeIfNotNull(context, "userPassword", user.getUserPassword());
            setAttributeIfNotNull(context, "telephoneNumber", user.getTelephoneNumber());
            setAttributeIfNotNull(context, "title", user.getTitle());
            setAttributeIfNotNull(context, "ou", user.getOu());
            setAttributeIfNotNull(context, "description", user.getDescription());

            // Set custom attributes; a collection is one attribute with several values
            for (Map.Entry<String, Object> entry : custom.entrySet()) {
                if (entry.getValue() instanceof Collection<?> values) {
                    context.setAttributeValues(entry.getKey(), values.toArray());
                } else {
                    context.setAttributeValue(entry.getKey(), entry.getValue());
                }
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
     * Reads every attribute of an entry, with all its values.
     * <p>
     * Unlike {@link #read}, nothing collapses to a first value: a single value is a scalar and
     * several are a list, for the ten names {@link LdapUser} types as well. Keys keep the
     * directory's spelling but are looked up ignoring case, as LDAP names are.
     * {@code userPassword} is never returned (see {@link #EXCLUDED_ATTRIBUTE}).
     *
     * @param dn the distinguished name
     * @return operation result containing the attributes or error
     */
    public OperationResult<Map<String, Object>> readAttributes(String dn) {
        log.debug("Reading LDAP attributes: {}", dn);

        try {
            Name name = LdapNameBuilder.newInstance(dn).build();

            Map<String, Object> attributes = ldapTemplate.lookup(name,
                    (AttributesMapper<Map<String, Object>>) EdirectoryConnector::attributeMap);

            return OperationResult.<Map<String, Object>>builder()
                    .success(true)
                    .data(attributes)
                    .message("Attributes read successfully")
                    .build();

        } catch (org.springframework.ldap.NameNotFoundException e) {
            log.warn("LDAP entry not found: {}", dn);
            return OperationResult.<Map<String, Object>>builder()
                    .success(false)
                    .message("Entry not found: " + dn)
                    .errorType(ErrorType.NOT_FOUND)
                    .throwable(e)
                    .build();
        } catch (Exception e) {
            log.error("Failed to read LDAP attributes '{}': {}", dn, e.getMessage());
            return OperationResult.<Map<String, Object>>builder()
                    .success(false)
                    .message("Failed to read entry: " + e.getMessage())
                    .errorType(ErrorType.LDAP_ERROR)
                    .throwable(e)
                    .build();
        }
    }

    private static Map<String, Object> attributeMap(Attributes attributes) throws NamingException {
        Map<String, Object> result = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        NamingEnumeration<? extends Attribute> all = attributes.getAll();
        while (all.hasMore()) {
            Attribute attribute = all.next();
            if (!EXCLUDED_ATTRIBUTE.equalsIgnoreCase(attribute.getID())) {
                result.put(attribute.getID(), valueOf(attribute));
            }
        }
        return result;
    }

    /**
     * A single-valued attribute reads as its scalar value; a multi-valued one as a list
     * of every value, so nothing is silently dropped.
     */
    private static Object valueOf(Attribute attribute) throws NamingException {
        if (attribute.size() == 1) {
            return attribute.get();
        }
        List<Object> values = new java.util.ArrayList<>(attribute.size());
        for (int i = 0; i < attribute.size(); i++) {
            values.add(attribute.get(i));
        }
        return values;
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
                // A collection is several values; an empty one replaces the attribute with
                // nothing, which removes it.
                javax.naming.directory.Attribute attr;
                if (entry.getValue() instanceof Collection<?> values) {
                    attr = new BasicAttribute(entry.getKey());
                    values.forEach(attr::add);
                } else {
                    attr = new BasicAttribute(entry.getKey(), entry.getValue());
                }
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
     * Removes attributes entirely.
     * <p>
     * {@link #update} can only REPLACE, which cannot express "this attribute had no value
     * before". Without removal, an undo that restores prior state would silently leave behind
     * every attribute the update introduced.
     *
     * @param dn             the distinguished name
     * @param attributeNames attributes to remove; an empty collection is a no-op success
     * @return operation result indicating success or failure
     */
    public OperationResult<Void> removeAttributes(String dn, java.util.Collection<String> attributeNames) {
        log.debug("Removing LDAP attributes {} from: {}", attributeNames, dn);

        if (attributeNames.isEmpty()) {
            return OperationResult.<Void>builder()
                    .success(true)
                    .message("No attributes to remove")
                    .build();
        }

        try {
            Name name = LdapNameBuilder.newInstance(dn).build();

            javax.naming.directory.ModificationItem[] mods =
                new javax.naming.directory.ModificationItem[attributeNames.size()];

            int i = 0;
            for (String attributeName : attributeNames) {
                mods[i++] = new javax.naming.directory.ModificationItem(
                    javax.naming.directory.DirContext.REMOVE_ATTRIBUTE,
                    new BasicAttribute(attributeName));
            }

            ldapTemplate.modifyAttributes(name, mods);

            log.info("LDAP attributes removed successfully from: {}", dn);

            return OperationResult.<Void>builder()
                    .success(true)
                    .message("Attributes removed successfully")
                    .build();

        } catch (Exception e) {
            log.error("Failed to remove LDAP attributes from '{}': {}", dn, e.getMessage());
            return OperationResult.<Void>builder()
                    .success(false)
                    .message("Failed to remove attributes: " + e.getMessage())
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
     * @param expectedAttributes map of attribute names to expected values: a value, a list (the exact set, any
     *                           order) or a {contains, excludes} map; see LdapValueMatcher
     * @return operation result indicating if verification passed
     */
    public OperationResult<Boolean> verifyAttributes(String dn, Map<String, Object> expectedAttributes) {
        log.debug("Verifying LDAP attributes for: {}", dn);

        try {
            OperationResult<Map<String, Object>> readResult = readAttributes(dn);

            if (!readResult.isSuccess()) {
                return OperationResult.<Boolean>builder()
                        .success(false)
                        .data(false)
                        .message("Failed to read entry: " + readResult.getMessage())
                        .errorType(readResult.getErrorType().orElse(ErrorType.LDAP_ERROR))
                        .throwable(readResult.getThrowable().orElse(null))
                        .build();
            }

            Map<String, Object> actualAttributes = readResult.getData().orElseThrow();

            for (Map.Entry<String, Object> entry : expectedAttributes.entrySet()) {
                String mismatch = LdapValueMatcher.match(
                        entry.getKey(), entry.getValue(), actualAttributes.get(entry.getKey()));

                if (mismatch != null) {
                    log.warn(mismatch);

                    return OperationResult.<Boolean>builder()
                            .success(false)
                            .data(false)
                            .message(mismatch)
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
    /**
     * The attribute names that have first-class getters on {@link LdapUser}. Everything else
     * read from the directory becomes a custom attribute.
     */
    private static final java.util.Set<String> MAPPED_ATTRIBUTES = java.util.Set.of(
            "cn", "sn", "givenName", "mail", "uid",
            "telephoneNumber", "title", "ou", "description");

    /**
     * Never surfaced. A password read back here would flow into an {@code as:} capture and
     * from there into the JSON result file — a credential written to disk by a test report.
     * The cost is that a password change cannot be undone by cleanup; that is the honest
     * trade, and it matches why ldap.delete registers no undo at all.
     */
    private static final String EXCLUDED_ATTRIBUTE = "userPassword";

    /**
     * Maps every attribute the directory returns, not a fixed list.
     * <p>
     * The previous version read ten hardcoded names and never populated custom attributes,
     * which made {@link #verifyAttributes} report {@code actual=null} for any other attribute
     * even when the directory genuinely held a value — a false failure, not a missed one.
     */
    private static class UserContextMapper extends AbstractContextMapper<LdapUser> {
        @Override
        protected LdapUser doMapFromContext(DirContextOperations context) {
            LdapUser user = new LdapUser();
            user.setDn(context.getDn().toString());

            Attributes attributes = context.getAttributes();
            if (attributes == null) {
                return user;
            }

            try {
                NamingEnumeration<? extends Attribute> all = attributes.getAll();
                while (all.hasMore()) {
                    Attribute attribute = all.next();
                    String id = attribute.getID();

                    if (EXCLUDED_ATTRIBUTE.equalsIgnoreCase(id)) {
                        continue;
                    }
                    if (MAPPED_ATTRIBUTES.contains(id)) {
                        // The first-class getters are typed String, so a multi-valued
                        // instance of one of these collapses to its first value — the same
                        // thing getStringAttribute did before.
                        setMapped(user, id, String.valueOf(attribute.get()));
                    } else {
                        user.addCustomAttribute(id, valueOf(attribute));
                    }
                }
            } catch (NamingException e) {
                // Returning a partially-populated entry would be a silent lie: callers would
                // read a missing attribute as absent rather than as unread. read() catches
                // this and reports it as an LDAP_ERROR.
                throw new org.springframework.ldap.UncategorizedLdapException(
                        "Failed to read attributes for " + context.getDn(), e);
            }

            return user;
        }

        private static void setMapped(LdapUser user, String id, String value) {
            switch (id) {
                case "cn" -> user.setCn(value);
                case "sn" -> user.setSn(value);
                case "givenName" -> user.setGivenName(value);
                case "mail" -> user.setMail(value);
                case "uid" -> user.setUid(value);
                case "telephoneNumber" -> user.setTelephoneNumber(value);
                case "title" -> user.setTitle(value);
                case "ou" -> user.setOu(value);
                case "description" -> user.setDescription(value);
                default -> throw new IllegalStateException("not a mapped attribute: " + id);
            }
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
