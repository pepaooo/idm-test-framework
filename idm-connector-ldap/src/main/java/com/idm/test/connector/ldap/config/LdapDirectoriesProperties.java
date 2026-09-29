package com.idm.test.connector.ldap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Several named LDAP directories, each with the same keys as {@link LdapProperties}:
 * <pre>
 * idm:
 *   test:
 *     default-directory: edirectory
 *     directories:
 *       edirectory:
 *         url: ldaps://edir.company.com:636
 *         base-dn: o=company
 *       ad:
 *         url: ldaps://dc01.company.com:636
 *         base-dn: dc=corp,dc=company,dc=com
 * </pre>
 * The older {@code idm.test.ldap} block is the directory named {@code default}.
 */
@ConfigurationProperties(prefix = "idm.test")
public class LdapDirectoriesProperties {

    private String defaultDirectory;
    private Map<String, LdapProperties> directories = new LinkedHashMap<>();

    public String getDefaultDirectory() {
        return defaultDirectory;
    }

    public void setDefaultDirectory(String defaultDirectory) {
        this.defaultDirectory = defaultDirectory;
    }

    public Map<String, LdapProperties> getDirectories() {
        return directories;
    }

    public void setDirectories(Map<String, LdapProperties> directories) {
        this.directories = directories;
    }
}
