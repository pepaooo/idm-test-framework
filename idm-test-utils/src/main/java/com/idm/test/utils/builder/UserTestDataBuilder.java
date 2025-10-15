package com.idm.test.utils.builder;

import com.idm.test.utils.data.TestDataGenerator;
import org.springframework.stereotype.Component;

/**
 * Fluent builder for creating test user data with random values.
 * Combines TestDataGenerator with UserTestData for easy test data creation.
 * <p>
 * Usage examples:
 * <pre>
 * // Create random user
 * UserTestData user = builder.createRandomUser();
 *
 * // Create user with specific values
 * UserTestData user = builder.withFirstName("John")
 *                            .withLastName("Doe")
 *                            .withEmail("john.doe@test.com")
 *                            .build();
 *
 * // Create user in specific department
 * UserTestData user = builder.inDepartment("Engineering").build();
 * </pre>
 */
@Component
public class UserTestDataBuilder {

    private final TestDataGenerator generator;
    private final UserTestData.Builder builder;

    public UserTestDataBuilder(TestDataGenerator generator) {
        this.generator = generator;
        this.builder = UserTestData.builder();
    }

    /**
     * Creates a fully populated random user with all standard attributes.
     *
     * @return random user test data
     */
    public UserTestData createRandomUser() {
        String firstName = generator.generateFirstName();
        String lastName = generator.generateLastName();

        return UserTestData.builder()
                .userId(generator.generateUserId())
                .firstName(firstName)
                .lastName(lastName)
                .email(generator.generateEmail(firstName, lastName))
                .phoneNumber(generator.generatePhoneNumber())
                .department(generator.generateDepartment())
                .jobTitle(generator.generateJobTitle())
                .employeeId(generator.generateEmployeeId())
                .costCenter(generator.generateCostCenter())
                .city(generator.generateCity())
                .country(generator.generateCountry())
                .streetAddress(generator.generateStreetAddress())
                .zipCode(generator.generateZipCode())
                .dateOfBirth(generator.generateDateOfBirth())
                .startDate(generator.generateStartDate())
                .password(generator.generatePassword())
                .build();
    }

    /**
     * Creates a minimal random user with only required attributes.
     *
     * @return minimal user test data
     */
    public UserTestData createMinimalUser() {
        String firstName = generator.generateFirstName();
        String lastName = generator.generateLastName();

        return UserTestData.builder()
                .userId(generator.generateUserId())
                .firstName(firstName)
                .lastName(lastName)
                .email(generator.generateEmail(firstName, lastName))
                .build();
    }

    /**
     * Creates a random user in a specific department.
     *
     * @param department the department name
     * @return random user in specified department
     */
    public UserTestData createUserInDepartment(String department) {
        UserTestData baseUser = createRandomUser();
        return UserTestData.builder()
                .userId(baseUser.getUserId())
                .firstName(baseUser.getFirstName())
                .lastName(baseUser.getLastName())
                .email(baseUser.getEmail())
                .phoneNumber(baseUser.getPhoneNumber())
                .department(department)
                .jobTitle(baseUser.getJobTitle())
                .employeeId(baseUser.getEmployeeId())
                .costCenter(baseUser.getCostCenter())
                .city(baseUser.getCity())
                .country(baseUser.getCountry())
                .streetAddress(baseUser.getStreetAddress())
                .zipCode(baseUser.getZipCode())
                .dateOfBirth(baseUser.getDateOfBirth())
                .startDate(baseUser.getStartDate())
                .password(baseUser.getPassword())
                .build();
    }

    // Fluent API methods

    public UserTestDataBuilder withUserId(String userId) {
        builder.userId(userId);
        return this;
    }

    public UserTestDataBuilder withRandomUserId() {
        builder.userId(generator.generateUserId());
        return this;
    }

    public UserTestDataBuilder withFirstName(String firstName) {
        builder.firstName(firstName);
        return this;
    }

    public UserTestDataBuilder withRandomFirstName() {
        builder.firstName(generator.generateFirstName());
        return this;
    }

    public UserTestDataBuilder withLastName(String lastName) {
        builder.lastName(lastName);
        return this;
    }

    public UserTestDataBuilder withRandomLastName() {
        builder.lastName(generator.generateLastName());
        return this;
    }

    public UserTestDataBuilder withEmail(String email) {
        builder.email(email);
        return this;
    }

    public UserTestDataBuilder withRandomEmail() {
        builder.email(generator.generateEmail());
        return this;
    }

    public UserTestDataBuilder withPhoneNumber(String phoneNumber) {
        builder.phoneNumber(phoneNumber);
        return this;
    }

    public UserTestDataBuilder withRandomPhoneNumber() {
        builder.phoneNumber(generator.generatePhoneNumber());
        return this;
    }

    public UserTestDataBuilder inDepartment(String department) {
        builder.department(department);
        return this;
    }

    public UserTestDataBuilder withRandomDepartment() {
        builder.department(generator.generateDepartment());
        return this;
    }

    public UserTestDataBuilder withJobTitle(String jobTitle) {
        builder.jobTitle(jobTitle);
        return this;
    }

    public UserTestDataBuilder withRandomJobTitle() {
        builder.jobTitle(generator.generateJobTitle());
        return this;
    }

    public UserTestDataBuilder withEmployeeId(String employeeId) {
        builder.employeeId(employeeId);
        return this;
    }

    public UserTestDataBuilder withRandomEmployeeId() {
        builder.employeeId(generator.generateEmployeeId());
        return this;
    }

    public UserTestDataBuilder withManager(String manager) {
        builder.manager(manager);
        return this;
    }

    public UserTestDataBuilder withCostCenter(String costCenter) {
        builder.costCenter(costCenter);
        return this;
    }

    public UserTestDataBuilder withRandomCostCenter() {
        builder.costCenter(generator.generateCostCenter());
        return this;
    }

    public UserTestDataBuilder inLocation(String location) {
        builder.location(location);
        return this;
    }

    public UserTestDataBuilder withCity(String city) {
        builder.city(city);
        return this;
    }

    public UserTestDataBuilder withRandomCity() {
        builder.city(generator.generateCity());
        return this;
    }

    public UserTestDataBuilder withCountry(String country) {
        builder.country(country);
        return this;
    }

    public UserTestDataBuilder withRandomCountry() {
        builder.country(generator.generateCountry());
        return this;
    }

    public UserTestDataBuilder withStreetAddress(String streetAddress) {
        builder.streetAddress(streetAddress);
        return this;
    }

    public UserTestDataBuilder withRandomStreetAddress() {
        builder.streetAddress(generator.generateStreetAddress());
        return this;
    }

    public UserTestDataBuilder withZipCode(String zipCode) {
        builder.zipCode(zipCode);
        return this;
    }

    public UserTestDataBuilder withRandomZipCode() {
        builder.zipCode(generator.generateZipCode());
        return this;
    }

    public UserTestDataBuilder withDateOfBirth(String dateOfBirth) {
        builder.dateOfBirth(dateOfBirth);
        return this;
    }

    public UserTestDataBuilder withRandomDateOfBirth() {
        builder.dateOfBirth(generator.generateDateOfBirth());
        return this;
    }

    public UserTestDataBuilder withStartDate(String startDate) {
        builder.startDate(startDate);
        return this;
    }

    public UserTestDataBuilder withRandomStartDate() {
        builder.startDate(generator.generateStartDate());
        return this;
    }

    public UserTestDataBuilder withPassword(String password) {
        builder.password(password);
        return this;
    }

    public UserTestDataBuilder withRandomPassword() {
        builder.password(generator.generatePassword());
        return this;
    }

    public UserTestDataBuilder withCustomAttribute(String key, String value) {
        builder.customAttribute(key, value);
        return this;
    }

    public UserTestData build() {
        return builder.build();
    }

    /**
     * Resets the builder to create a new user.
     *
     * @return new builder instance
     */
    public UserTestDataBuilder reset() {
        return new UserTestDataBuilder(generator);
    }
}
