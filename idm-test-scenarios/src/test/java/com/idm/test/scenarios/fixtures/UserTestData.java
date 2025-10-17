package com.idm.test.scenarios.fixtures;

import com.idm.test.utils.data.TestDataGenerator;

import java.util.HashMap;
import java.util.Map;

/**
 * Test data builder for user objects.
 * Provides fluent API for creating test users with realistic data.
 */
public class UserTestData {

    private final TestDataGenerator generator;

    private String userId;
    private String firstName;
    private String lastName;
    private String email;
    private String department;
    private String jobTitle;
    private String employeeId;
    private String phone;
    private String city;
    private String country;
    private String startDate;
    private boolean active = true;

    public UserTestData(TestDataGenerator generator) {
        this.generator = generator;
    }

    /**
     * Create a new user builder with random data.
     */
    public static UserTestData randomUser(TestDataGenerator generator) {
        UserTestData data = new UserTestData(generator);
        data.userId = generator.generateUserId("test");
        data.firstName = generator.generateFirstName();
        data.lastName = generator.generateLastName();
        data.email = generator.generateEmail(data.firstName, data.lastName);
        data.department = generator.generateDepartment();
        data.jobTitle = generator.generateJobTitle();
        data.employeeId = generator.generateEmployeeId();
        data.phone = generator.generatePhoneNumber();
        data.city = generator.generateCity();
        data.country = generator.generateCountry();
        data.startDate = generator.generateStartDate();
        return data;
    }

    /**
     * Create a new user builder with minimal required fields.
     */
    public static UserTestData minimalUser(TestDataGenerator generator) {
        UserTestData data = new UserTestData(generator);
        data.userId = generator.generateUserId("test");
        data.firstName = generator.generateFirstName();
        data.lastName = generator.generateLastName();
        data.email = generator.generateEmail();
        return data;
    }

    // Fluent setters

    public UserTestData withUserId(String userId) {
        this.userId = userId;
        return this;
    }

    public UserTestData withFirstName(String firstName) {
        this.firstName = firstName;
        return this;
    }

    public UserTestData withLastName(String lastName) {
        this.lastName = lastName;
        return this;
    }

    public UserTestData withEmail(String email) {
        this.email = email;
        return this;
    }

    public UserTestData withDepartment(String department) {
        this.department = department;
        return this;
    }

    public UserTestData withJobTitle(String jobTitle) {
        this.jobTitle = jobTitle;
        return this;
    }

    public UserTestData withEmployeeId(String employeeId) {
        this.employeeId = employeeId;
        return this;
    }

    public UserTestData withPhone(String phone) {
        this.phone = phone;
        return this;
    }

    public UserTestData withCity(String city) {
        this.city = city;
        return this;
    }

    public UserTestData withCountry(String country) {
        this.country = country;
        return this;
    }

    public UserTestData withStartDate(String startDate) {
        this.startDate = startDate;
        return this;
    }

    public UserTestData withActive(boolean active) {
        this.active = active;
        return this;
    }

    // Getters

    public String getUserId() {
        return userId;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public String getFullName() {
        return firstName + " " + lastName;
    }

    public String getEmail() {
        return email;
    }

    public String getDepartment() {
        return department;
    }

    public String getJobTitle() {
        return jobTitle;
    }

    public String getEmployeeId() {
        return employeeId;
    }

    public String getPhone() {
        return phone;
    }

    public String getCity() {
        return city;
    }

    public String getCountry() {
        return country;
    }

    public String getStartDate() {
        return startDate;
    }

    public boolean isActive() {
        return active;
    }

    /**
     * Build LDAP DN for this user.
     */
    public String buildLdapDn(String baseDn) {
        return "cn=" + userId + "," + baseDn;
    }

    /**
     * Build LDAP DN with custom OU.
     */
    public String buildLdapDn(String ou, String baseDn) {
        return "cn=" + userId + ",ou=" + ou + "," + baseDn;
    }

    /**
     * Convert to LDAP attributes map.
     */
    public Map<String, String> toLdapAttributes() {
        Map<String, String> attributes = new HashMap<>();
        attributes.put("cn", userId);
        attributes.put("givenName", firstName);
        attributes.put("sn", lastName);
        attributes.put("mail", email);
        if (department != null) attributes.put("ou", department);
        if (jobTitle != null) attributes.put("title", jobTitle);
        if (employeeId != null) attributes.put("employeeNumber", employeeId);
        if (phone != null) attributes.put("telephoneNumber", phone);
        if (city != null) attributes.put("l", city);
        if (country != null) attributes.put("c", country);
        return attributes;
    }

    /**
     * Convert to LdapUser object.
     */
    public com.idm.test.connector.ldap.model.LdapUser toLdapUser(String dn) {
        com.idm.test.connector.ldap.model.LdapUser user = new com.idm.test.connector.ldap.model.LdapUser();
        user.setDn(dn);
        user.setCn(userId);
        user.setSn(lastName);
        user.setGivenName(firstName);
        user.setMail(email);
        user.setUid(userId);
        user.setTelephoneNumber(phone);
        user.setTitle(jobTitle);
        user.setOu(department);

        // Add custom attributes
        if (employeeId != null) user.addCustomAttribute("employeeNumber", employeeId);
        if (city != null) user.addCustomAttribute("l", city);
        if (country != null) user.addCustomAttribute("c", country);
        if (startDate != null) user.addCustomAttribute("startDate", startDate);

        return user;
    }

    /**
     * Convert to database row map.
     */
    public Map<String, Object> toDatabaseRow() {
        Map<String, Object> row = new HashMap<>();
        row.put("user_id", userId);
        row.put("first_name", firstName);
        row.put("last_name", lastName);
        row.put("email", email);
        row.put("department", department);
        row.put("job_title", jobTitle);
        row.put("employee_id", employeeId);
        row.put("phone", phone);
        row.put("city", city);
        row.put("country", country);
        row.put("start_date", startDate);
        row.put("active", active);
        return row;
    }

    /**
     * Convert to REST API JSON map.
     */
    public Map<String, Object> toRestJson() {
        Map<String, Object> json = new HashMap<>();
        json.put("userId", userId);
        json.put("firstName", firstName);
        json.put("lastName", lastName);
        json.put("email", email);
        json.put("department", department);
        json.put("jobTitle", jobTitle);
        json.put("employeeId", employeeId);
        json.put("phone", phone);
        json.put("city", city);
        json.put("country", country);
        json.put("startDate", startDate);
        json.put("active", active);
        return json;
    }

    @Override
    public String toString() {
        return "UserTestData{" +
                "userId='" + userId + '\'' +
                ", name='" + firstName + " " + lastName + '\'' +
                ", email='" + email + '\'' +
                ", department='" + department + '\'' +
                ", active=" + active +
                '}';
    }
}
