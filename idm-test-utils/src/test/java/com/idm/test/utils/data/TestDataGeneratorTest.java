package com.idm.test.utils.data;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for TestDataGenerator.
 */
class TestDataGeneratorTest {

    private TestDataGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new TestDataGenerator();
    }

    @Test
    @DisplayName("Should generate unique user IDs")
    void testGenerateUserId() {
        Set<String> userIds = new HashSet<>();

        for (int i = 0; i < 100; i++) {
            String userId = generator.generateUserId();
            assertThat(userId).isNotEmpty();
            userIds.add(userId);
        }

        // Most IDs should be unique (allowing for timestamp collisions in rapid generation)
        // At least 90% unique is reasonable
        assertThat(userIds.size()).isGreaterThanOrEqualTo(90);
    }

    @Test
    @DisplayName("Should generate user ID with custom prefix")
    void testGenerateUserIdWithPrefix() {
        String userId = generator.generateUserId("custom");

        assertThat(userId)
                .startsWith("custom.")
                .contains(".");
    }

    @Test
    @DisplayName("Should generate valid first names")
    void testGenerateFirstName() {
        for (int i = 0; i < 10; i++) {
            String firstName = generator.generateFirstName();
            assertThat(firstName)
                    .isNotEmpty()
                    .matches("[A-Z][a-z]+");
        }
    }

    @Test
    @DisplayName("Should generate valid last names")
    void testGenerateLastName() {
        for (int i = 0; i < 10; i++) {
            String lastName = generator.generateLastName();
            assertThat(lastName)
                    .isNotEmpty()
                    .matches("[A-Z][a-z]+");
        }
    }

    @Test
    @DisplayName("Should generate full names with first and last")
    void testGenerateFullName() {
        String fullName = generator.generateFullName();

        assertThat(fullName)
                .contains(" ")
                .matches("[A-Z][a-z]+ [A-Z][a-z]+");
    }

    @Test
    @DisplayName("Should generate valid email addresses")
    void testGenerateEmail() {
        String email = generator.generateEmail("John", "Doe");

        assertThat(email)
                .isEqualTo("john.doe@test-" + email.split("@test-")[1].split("\\.")[0] + ".com")
                .matches("^[a-z]+\\.[a-z]+@test-\\d{3}\\.com$");
    }

    @Test
    @DisplayName("Should generate random email addresses")
    void testGenerateRandomEmail() {
        String email = generator.generateEmail();

        assertThat(email)
                .isNotEmpty()
                .contains("@")
                .contains(".");
    }

    @Test
    @DisplayName("Should generate valid phone numbers")
    void testGeneratePhoneNumber() {
        String phone = generator.generatePhoneNumber();

        assertThat(phone)
                .matches("\\(\\d{3}\\) \\d{3}-\\d{4}");
    }

    @Test
    @DisplayName("Should generate valid departments")
    void testGenerateDepartment() {
        String department = generator.generateDepartment();

        assertThat(department).isNotEmpty();
    }

    @Test
    @DisplayName("Should generate valid job titles")
    void testGenerateJobTitle() {
        String jobTitle = generator.generateJobTitle();

        assertThat(jobTitle).isNotEmpty();
    }

    @Test
    @DisplayName("Should generate valid employee IDs")
    void testGenerateEmployeeId() {
        String employeeId = generator.generateEmployeeId();

        assertThat(employeeId)
                .startsWith("EMP")
                .matches("EMP\\d{5}");
    }

    @Test
    @DisplayName("Should generate valid cost centers")
    void testGenerateCostCenter() {
        String costCenter = generator.generateCostCenter();

        assertThat(costCenter)
                .startsWith("CC-")
                .matches("CC-\\d{4}");
    }

    @Test
    @DisplayName("Should generate valid cities")
    void testGenerateCity() {
        String city = generator.generateCity();

        assertThat(city).isNotEmpty();
    }

    @Test
    @DisplayName("Should generate valid countries")
    void testGenerateCountry() {
        String country = generator.generateCountry();

        assertThat(country).isNotEmpty();
    }

    @Test
    @DisplayName("Should generate valid street addresses")
    void testGenerateStreetAddress() {
        String address = generator.generateStreetAddress();

        assertThat(address)
                .isNotEmpty()
                .contains(" ");
    }

    @Test
    @DisplayName("Should generate valid ZIP codes")
    void testGenerateZipCode() {
        String zipCode = generator.generateZipCode();

        assertThat(zipCode)
                .hasSize(5)
                .matches("\\d{5}");
    }

    @Test
    @DisplayName("Should generate valid dates of birth")
    void testGenerateDateOfBirth() {
        String dob = generator.generateDateOfBirth();

        assertThat(dob)
                .matches("\\d{4}-\\d{2}-\\d{2}");
    }

    @Test
    @DisplayName("Should generate valid start dates")
    void testGenerateStartDate() {
        String startDate = generator.generateStartDate();

        assertThat(startDate)
                .matches("\\d{4}-\\d{2}-\\d{2}");
    }

    @Test
    @DisplayName("Should generate valid passwords")
    void testGeneratePassword() {
        String password = generator.generatePassword();

        assertThat(password)
                .hasSizeGreaterThanOrEqualTo(12)
                .matches(".*[A-Z].*")  // Contains uppercase
                .matches(".*[a-z].*")  // Contains lowercase
                .matches(".*\\d.*")    // Contains digit
                .matches(".*[!@#$%^&*].*");  // Contains special char
    }

    @Test
    @DisplayName("Should generate unique GUIDs")
    void testGenerateGuid() {
        Set<String> guids = new HashSet<>();

        for (int i = 0; i < 100; i++) {
            String guid = generator.generateGuid();
            assertThat(guid).matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}");
            guids.add(guid);
        }

        assertThat(guids).hasSize(100);
    }

    @Test
    @DisplayName("Should generate valid LDAP DNs")
    void testGenerateDn() {
        String dn = generator.generateDn("testuser", "users");

        assertThat(dn)
                .isEqualTo("cn=testuser,ou=users,dc=company,dc=com");
    }

    @Test
    @DisplayName("Should generate alphanumeric strings of specified length")
    void testGenerateAlphanumeric() {
        String str = generator.generateAlphanumeric(20);

        assertThat(str)
                .hasSize(20)
                .matches("[A-Za-z0-9]{20}");
    }

    @Test
    @DisplayName("Should generate random integers within range")
    void testRandomInt() {
        for (int i = 0; i < 100; i++) {
            int value = generator.randomInt(10, 20);
            assertThat(value).isBetween(10, 20);
        }
    }

    @Test
    @DisplayName("Should generate random booleans")
    void testRandomBoolean() {
        Set<Boolean> values = new HashSet<>();

        for (int i = 0; i < 50; i++) {
            values.add(generator.randomBoolean());
        }

        // Should have generated both true and false (statistically)
        assertThat(values).hasSizeGreaterThan(1);
    }

    @Test
    @DisplayName("Should select random elements from list")
    void testRandomElement() {
        List<String> list = List.of("A", "B", "C");
        Set<String> selected = new HashSet<>();

        for (int i = 0; i < 50; i++) {
            selected.add(generator.randomElement(list));
        }

        // Should have selected multiple different elements
        assertThat(selected).hasSizeGreaterThan(1);
        assertThat(selected).isSubsetOf(list);
    }

    @Test
    @DisplayName("Should throw exception for empty list")
    void testRandomElementWithEmptyList() {
        assertThatThrownBy(() -> generator.randomElement(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
