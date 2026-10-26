package com.idm.test.connector.ldap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for LDAP connection.
 * <p>
 * Example configuration in application.yml:
 * <pre>
 * idm:
 *   test:
 *     ldap:
 *       url: ldap://localhost:389
 *       base-dn: dc=company,dc=com
 *       user-dn: cn=admin,dc=company,dc=com
 *       password: admin
 *       pooled: true
 *       min-idle: 1
 *       max-active: 8
 * </pre>
 */
@ConfigurationProperties(prefix = "idm.test.ldap")
public class LdapProperties {

    private String label;

    public String getLabel() { return label; }

    public void setLabel(String label) { this.label = label; }

    private String url;
    private String baseDn;
    private String userDn;
    private String password;
    private boolean pooled = true;
    private int minIdle = 1;
    private int maxActive = 8;
    private int maxTotal = 8;
    private int maxWait = -1;

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getBaseDn() {
        return baseDn;
    }

    public void setBaseDn(String baseDn) {
        this.baseDn = baseDn;
    }

    public String getUserDn() {
        return userDn;
    }

    public void setUserDn(String userDn) {
        this.userDn = userDn;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public boolean isPooled() {
        return pooled;
    }

    public void setPooled(boolean pooled) {
        this.pooled = pooled;
    }

    public int getMinIdle() {
        return minIdle;
    }

    public void setMinIdle(int minIdle) {
        this.minIdle = minIdle;
    }

    public int getMaxActive() {
        return maxActive;
    }

    public void setMaxActive(int maxActive) {
        this.maxActive = maxActive;
    }

    public int getMaxTotal() {
        return maxTotal;
    }

    public void setMaxTotal(int maxTotal) {
        this.maxTotal = maxTotal;
    }

    public int getMaxWait() {
        return maxWait;
    }

    public void setMaxWait(int maxWait) {
        this.maxWait = maxWait;
    }
}
