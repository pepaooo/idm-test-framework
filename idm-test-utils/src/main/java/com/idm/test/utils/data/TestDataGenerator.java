package com.idm.test.utils.data;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Generates random test data for various test scenarios.
 * Thread-safe and suitable for parallel test execution.
 */
@Component
public class TestDataGenerator {

    private static final Random RANDOM = new SecureRandom();

    private static final List<String> FIRST_NAMES = List.of(
            "John", "Jane", "Michael", "Sarah", "David", "Emily", "James", "Emma",
            "Robert", "Olivia", "William", "Ava", "Richard", "Sophia", "Joseph", "Isabella"
    );

    private static final List<String> LAST_NAMES = List.of(
            "Smith", "Johnson", "Williams", "Brown", "Jones", "Garcia", "Miller", "Davis",
            "Rodriguez", "Martinez", "Hernandez", "Lopez", "Wilson", "Anderson", "Thomas", "Taylor"
    );

    private static final List<String> DEPARTMENTS = List.of(
            "Engineering", "Marketing", "Sales", "HR", "Finance", "IT", "Operations",
            "Customer Service", "Product", "Legal", "Research", "Development"
    );

    private static final List<String> JOB_TITLES = List.of(
            "Software Engineer", "Product Manager", "Sales Representative", "HR Manager",
            "Financial Analyst", "IT Specialist", "Operations Manager", "Support Engineer",
            "Marketing Specialist", "Senior Developer", "Team Lead", "Director"
    );

    private static final List<String> CITIES = List.of(
            "New York", "Los Angeles", "Chicago", "Houston", "Phoenix", "Philadelphia",
            "San Antonio", "San Diego", "Dallas", "San Jose", "Austin", "Seattle"
    );

    private static final List<String> COUNTRIES = List.of(
            "USA", "Canada", "UK", "Germany", "France", "Spain", "Italy", "Australia"
    );

    /**
     * Generates a unique user ID with timestamp for uniqueness.
     *
     * @return unique user ID
     */
    public String generateUserId() {
        return "test.user." + System.currentTimeMillis() + "." + randomInt(1000, 9999);
    }

    /**
     * Generates a unique user ID with custom prefix.
     *
     * @param prefix the prefix for the user ID
     * @return unique user ID with prefix
     */
    public String generateUserId(String prefix) {
        return prefix + "." + System.currentTimeMillis() + "." + randomInt(1000, 9999);
    }

    /**
     * Generates a random first name.
     *
     * @return random first name
     */
    public String generateFirstName() {
        return randomElement(FIRST_NAMES);
    }

    /**
     * Generates a random last name.
     *
     * @return random last name
     */
    public String generateLastName() {
        return randomElement(LAST_NAMES);
    }

    /**
     * Generates a full name (first + last).
     *
     * @return random full name
     */
    public String generateFullName() {
        return generateFirstName() + " " + generateLastName();
    }

    /**
     * Generates an email address based on first and last name.
     *
     * @param firstName first name
     * @param lastName  last name
     * @return email address
     */
    public String generateEmail(String firstName, String lastName) {
        return firstName.toLowerCase() + "." + lastName.toLowerCase() +
                "@test-" + randomInt(100, 999) + ".com";
    }

    /**
     * Generates a random email address.
     *
     * @return random email address
     */
    public String generateEmail() {
        return generateEmail(generateFirstName(), generateLastName());
    }

    /**
     * Generates a random phone number in format (XXX) XXX-XXXX.
     *
     * @return random phone number
     */
    public String generatePhoneNumber() {
        return String.format("(%03d) %03d-%04d",
                randomInt(200, 999),
                randomInt(200, 999),
                randomInt(1000, 9999));
    }

    /**
     * Generates a random department name.
     *
     * @return random department
     */
    public String generateDepartment() {
        return randomElement(DEPARTMENTS);
    }

    /**
     * Generates a random job title.
     *
     * @return random job title
     */
    public String generateJobTitle() {
        return randomElement(JOB_TITLES);
    }

    /**
     * Generates a random employee ID.
     *
     * @return random employee ID
     */
    public String generateEmployeeId() {
        return "EMP" + randomInt(10000, 99999);
    }

    /**
     * Generates a random cost center code.
     *
     * @return random cost center
     */
    public String generateCostCenter() {
        return "CC-" + randomInt(1000, 9999);
    }

    /**
     * Generates a random city.
     *
     * @return random city
     */
    public String generateCity() {
        return randomElement(CITIES);
    }

    /**
     * Generates a random country.
     *
     * @return random country
     */
    public String generateCountry() {
        return randomElement(COUNTRIES);
    }

    /**
     * Generates a random street address.
     *
     * @return random street address
     */
    public String generateStreetAddress() {
        return randomInt(100, 9999) + " " +
                randomElement(List.of("Main", "Oak", "Pine", "Maple", "Cedar", "Elm", "Park")) +
                " " +
                randomElement(List.of("Street", "Avenue", "Boulevard", "Drive", "Lane", "Road"));
    }

    /**
     * Generates a random ZIP code.
     *
     * @return random ZIP code
     */
    public String generateZipCode() {
        return String.format("%05d", randomInt(10000, 99999));
    }

    /**
     * Generates a random date of birth (age between 22 and 65).
     *
     * @return date of birth in ISO format (yyyy-MM-dd)
     */
    public String generateDateOfBirth() {
        int age = randomInt(22, 65);
        LocalDate dob = LocalDate.now().minusYears(age).minusDays(randomInt(0, 365));
        return dob.format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    /**
     * Generates a random start date (within last 10 years).
     *
     * @return start date in ISO format
     */
    public String generateStartDate() {
        int daysAgo = randomInt(0, 3650); // 0 to 10 years
        LocalDate startDate = LocalDate.now().minusDays(daysAgo);
        return startDate.format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    /**
     * Generates a random password meeting complexity requirements.
     *
     * @return random password
     */
    public String generatePassword() {
        // Password with uppercase, lowercase, digit, and special char
        String upper = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        String lower = "abcdefghijklmnopqrstuvwxyz";
        String digits = "0123456789";
        String special = "!@#$%^&*";

        StringBuilder password = new StringBuilder();
        password.append(upper.charAt(RANDOM.nextInt(upper.length())));
        password.append(lower.charAt(RANDOM.nextInt(lower.length())));
        password.append(digits.charAt(RANDOM.nextInt(digits.length())));
        password.append(special.charAt(RANDOM.nextInt(special.length())));

        String allChars = upper + lower + digits + special;
        for (int i = 0; i < 8; i++) {
            password.append(allChars.charAt(RANDOM.nextInt(allChars.length())));
        }

        // Shuffle the password
        return shuffleString(password.toString());
    }

    /**
     * Generates a unique GUID/UUID.
     *
     * @return UUID string
     */
    public String generateGuid() {
        return UUID.randomUUID().toString();
    }

    /**
     * Generates a random distinguished name (DN) for LDAP.
     *
     * @param userId user identifier
     * @param ou     organizational unit
     * @return LDAP DN
     */
    public String generateDn(String userId, String ou) {
        return "cn=" + userId + ",ou=" + ou + ",dc=company,dc=com";
    }

    /**
     * Generates a random alphanumeric string of specified length.
     *
     * @param length desired length
     * @return random string
     */
    public String generateAlphanumeric(int length) {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(RANDOM.nextInt(chars.length())));
        }
        return sb.toString();
    }

    /**
     * Generates a random integer between min (inclusive) and max (inclusive).
     *
     * @param min minimum value
     * @param max maximum value
     * @return random integer
     */
    public int randomInt(int min, int max) {
        return RANDOM.nextInt(max - min + 1) + min;
    }

    /**
     * Generates a random boolean value.
     *
     * @return random boolean
     */
    public boolean randomBoolean() {
        return RANDOM.nextBoolean();
    }

    /**
     * Selects a random element from a list.
     *
     * @param list the list to select from
     * @param <T>  element type
     * @return random element
     */
    public <T> T randomElement(List<T> list) {
        if (list == null || list.isEmpty()) {
            throw new IllegalArgumentException("List cannot be null or empty");
        }
        return list.get(RANDOM.nextInt(list.size()));
    }

    /**
     * Shuffles the characters in a string.
     *
     * @param input the string to shuffle
     * @return shuffled string
     */
    private String shuffleString(String input) {
        char[] chars = input.toCharArray();
        for (int i = chars.length - 1; i > 0; i--) {
            int j = RANDOM.nextInt(i + 1);
            char temp = chars[i];
            chars[i] = chars[j];
            chars[j] = temp;
        }
        return new String(chars);
    }
}
