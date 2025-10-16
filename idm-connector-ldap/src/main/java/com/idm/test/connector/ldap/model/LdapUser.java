package com.idm.test.connector.ldap.model;

import java.util.HashMap;
import java.util.Map;

/**
 * Domain model for LDAP user object.
 * Represents a user in eDirectory/LDAP with standard and custom attributes.
 */
public class LdapUser {

    private String dn;  // Distinguished Name
    private String cn;  // Common Name
    private String sn;  // Surname
    private String givenName;
    private String mail;
    private String uid;
    private String userPassword;
    private String telephoneNumber;
    private String title;
    private String ou;  // Organizational Unit
    private String description;
    private Map<String, Object> customAttributes = new HashMap<>();

    public LdapUser() {
    }

    public LdapUser(String dn, String cn) {
        this.dn = dn;
        this.cn = cn;
    }

    public String getDn() {
        return dn;
    }

    public void setDn(String dn) {
        this.dn = dn;
    }

    public String getCn() {
        return cn;
    }

    public void setCn(String cn) {
        this.cn = cn;
    }

    public String getSn() {
        return sn;
    }

    public void setSn(String sn) {
        this.sn = sn;
    }

    public String getGivenName() {
        return givenName;
    }

    public void setGivenName(String givenName) {
        this.givenName = givenName;
    }

    public String getMail() {
        return mail;
    }

    public void setMail(String mail) {
        this.mail = mail;
    }

    public String getUid() {
        return uid;
    }

    public void setUid(String uid) {
        this.uid = uid;
    }

    public String getUserPassword() {
        return userPassword;
    }

    public void setUserPassword(String userPassword) {
        this.userPassword = userPassword;
    }

    public String getTelephoneNumber() {
        return telephoneNumber;
    }

    public void setTelephoneNumber(String telephoneNumber) {
        this.telephoneNumber = telephoneNumber;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getOu() {
        return ou;
    }

    public void setOu(String ou) {
        this.ou = ou;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Map<String, Object> getCustomAttributes() {
        return customAttributes;
    }

    public void setCustomAttributes(Map<String, Object> customAttributes) {
        this.customAttributes = customAttributes;
    }

    public void addCustomAttribute(String name, Object value) {
        this.customAttributes.put(name, value);
    }

    @Override
    public String toString() {
        return "LdapUser{" +
                "dn='" + dn + '\'' +
                ", cn='" + cn + '\'' +
                ", sn='" + sn + '\'' +
                ", mail='" + mail + '\'' +
                ", uid='" + uid + '\'' +
                '}';
    }
}
