package com.idm.test.utils.builder;

import java.util.HashMap;
import java.util.Map;

/**
 * Represents test user data with all common attributes.
 * Immutable once built.
 */
public final class UserTestData {

    private final String userId;
    private final String firstName;
    private final String lastName;
    private final String email;
    private final String phoneNumber;
    private final String department;
    private final String jobTitle;
    private final String employeeId;
    private final String manager;
    private final String costCenter;
    private final String location;
    private final String city;
    private final String country;
    private final String streetAddress;
    private final String zipCode;
    private final String dateOfBirth;
    private final String startDate;
    private final String password;
    private final Map<String, String> customAttributes;

    private UserTestData(Builder builder) {
        this.userId = builder.userId;
        this.firstName = builder.firstName;
        this.lastName = builder.lastName;
        this.email = builder.email;
        this.phoneNumber = builder.phoneNumber;
        this.department = builder.department;
        this.jobTitle = builder.jobTitle;
        this.employeeId = builder.employeeId;
        this.manager = builder.manager;
        this.costCenter = builder.costCenter;
        this.location = builder.location;
        this.city = builder.city;
        this.country = builder.country;
        this.streetAddress = builder.streetAddress;
        this.zipCode = builder.zipCode;
        this.dateOfBirth = builder.dateOfBirth;
        this.startDate = builder.startDate;
        this.password = builder.password;
        this.customAttributes = Map.copyOf(builder.customAttributes);
    }

    public static Builder builder() {
        return new Builder();
    }

    // Getters
    public String getUserId() { return userId; }
    public String getFirstName() { return firstName; }
    public String getLastName() { return lastName; }
    public String getFullName() { return firstName + " " + lastName; }
    public String getEmail() { return email; }
    public String getPhoneNumber() { return phoneNumber; }
    public String getDepartment() { return department; }
    public String getJobTitle() { return jobTitle; }
    public String getEmployeeId() { return employeeId; }
    public String getManager() { return manager; }
    public String getCostCenter() { return costCenter; }
    public String getLocation() { return location; }
    public String getCity() { return city; }
    public String getCountry() { return country; }
    public String getStreetAddress() { return streetAddress; }
    public String getZipCode() { return zipCode; }
    public String getDateOfBirth() { return dateOfBirth; }
    public String getStartDate() { return startDate; }
    public String getPassword() { return password; }
    public Map<String, String> getCustomAttributes() { return customAttributes; }

    /**
     * Returns a map representation of all attributes.
     *
     * @return map of all non-null attributes
     */
    public Map<String, String> toMap() {
        Map<String, String> map = new HashMap<>();
        if (userId != null) map.put("userId", userId);
        if (firstName != null) map.put("firstName", firstName);
        if (lastName != null) map.put("lastName", lastName);
        if (email != null) map.put("email", email);
        if (phoneNumber != null) map.put("phoneNumber", phoneNumber);
        if (department != null) map.put("department", department);
        if (jobTitle != null) map.put("jobTitle", jobTitle);
        if (employeeId != null) map.put("employeeId", employeeId);
        if (manager != null) map.put("manager", manager);
        if (costCenter != null) map.put("costCenter", costCenter);
        if (location != null) map.put("location", location);
        if (city != null) map.put("city", city);
        if (country != null) map.put("country", country);
        if (streetAddress != null) map.put("streetAddress", streetAddress);
        if (zipCode != null) map.put("zipCode", zipCode);
        if (dateOfBirth != null) map.put("dateOfBirth", dateOfBirth);
        if (startDate != null) map.put("startDate", startDate);
        map.putAll(customAttributes);
        return map;
    }

    @Override
    public String toString() {
        return "UserTestData{" +
                "userId='" + userId + '\'' +
                ", name='" + getFullName() + '\'' +
                ", email='" + email + '\'' +
                ", department='" + department + '\'' +
                '}';
    }

    /**
     * Builder for UserTestData.
     */
    public static final class Builder {
        private String userId;
        private String firstName;
        private String lastName;
        private String email;
        private String phoneNumber;
        private String department;
        private String jobTitle;
        private String employeeId;
        private String manager;
        private String costCenter;
        private String location;
        private String city;
        private String country;
        private String streetAddress;
        private String zipCode;
        private String dateOfBirth;
        private String startDate;
        private String password;
        private final Map<String, String> customAttributes = new HashMap<>();

        private Builder() {}

        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public Builder firstName(String firstName) {
            this.firstName = firstName;
            return this;
        }

        public Builder lastName(String lastName) {
            this.lastName = lastName;
            return this;
        }

        public Builder email(String email) {
            this.email = email;
            return this;
        }

        public Builder phoneNumber(String phoneNumber) {
            this.phoneNumber = phoneNumber;
            return this;
        }

        public Builder department(String department) {
            this.department = department;
            return this;
        }

        public Builder jobTitle(String jobTitle) {
            this.jobTitle = jobTitle;
            return this;
        }

        public Builder employeeId(String employeeId) {
            this.employeeId = employeeId;
            return this;
        }

        public Builder manager(String manager) {
            this.manager = manager;
            return this;
        }

        public Builder costCenter(String costCenter) {
            this.costCenter = costCenter;
            return this;
        }

        public Builder location(String location) {
            this.location = location;
            return this;
        }

        public Builder city(String city) {
            this.city = city;
            return this;
        }

        public Builder country(String country) {
            this.country = country;
            return this;
        }

        public Builder streetAddress(String streetAddress) {
            this.streetAddress = streetAddress;
            return this;
        }

        public Builder zipCode(String zipCode) {
            this.zipCode = zipCode;
            return this;
        }

        public Builder dateOfBirth(String dateOfBirth) {
            this.dateOfBirth = dateOfBirth;
            return this;
        }

        public Builder startDate(String startDate) {
            this.startDate = startDate;
            return this;
        }

        public Builder password(String password) {
            this.password = password;
            return this;
        }

        public Builder customAttribute(String key, String value) {
            this.customAttributes.put(key, value);
            return this;
        }

        public Builder customAttributes(Map<String, String> attributes) {
            this.customAttributes.putAll(attributes);
            return this;
        }

        public UserTestData build() {
            return new UserTestData(this);
        }
    }
}
