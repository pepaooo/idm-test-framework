package com.idm.test.engine;

import com.idm.test.engine.i18n.KeyedArgumentException;
import com.idm.test.engine.model.FixtureSpec;
import com.idm.test.engine.spi.Message;
import com.idm.test.utils.data.TestDataGenerator;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Turns fixture declarations into the values a scenario interpolates.
 * <p>
 * Only one generator exists — "random-user" — because only one is needed. Adding generators
 * speculatively would grow a vocabulary nothing exercises.
 */
public final class FixtureFactory {

    private static final String RANDOM_USER = "random-user";

    /**
     * Generator names this factory understands. Exposed so {@code ScenarioValidator} can
     * reject an unknown {@code generate:} value at load time instead of letting it reach
     * {@link #build}, where it would only surface once a scenario is already running.
     */
    public static final Set<String> SUPPORTED_GENERATORS = Set.of(RANDOM_USER);

    private final TestDataGenerator generator;

    public FixtureFactory(TestDataGenerator generator) {
        this.generator = generator;
    }

    /**
     * @param fixtures fixture name to declaration
     * @return fixture name to its attribute map, ready for interpolation
     */
    public Map<String, Object> build(Map<String, FixtureSpec> fixtures) {
        Map<String, Object> scope = new LinkedHashMap<>();
        fixtures.forEach((name, spec) -> scope.put(name, buildOne(spec)));
        return scope;
    }

    private Map<String, Object> buildOne(FixtureSpec spec) {
        Map<String, Object> values = new LinkedHashMap<>();

        if (spec.generate() != null) {
            if (!SUPPORTED_GENERATORS.contains(spec.generate())) {
                throw new KeyedArgumentException(
                        Message.of("engine.fixture.unknownGenerator", spec.generate(), SUPPORTED_GENERATORS));
            }
            values.putAll(randomUser());
        }

        // Literals win: a scenario that pins a department means it.
        values.putAll(spec.values());
        return values;
    }

    private Map<String, Object> randomUser() {
        String firstName = generator.generateFirstName();
        String lastName = generator.generateLastName();

        Map<String, Object> user = new LinkedHashMap<>();
        user.put("userId", generator.generateUserId());
        user.put("firstName", firstName);
        user.put("lastName", lastName);
        user.put("email", generator.generateEmail(firstName, lastName));
        user.put("department", generator.generateDepartment());
        user.put("jobTitle", generator.generateJobTitle());
        user.put("phoneNumber", generator.generatePhoneNumber());
        user.put("employeeId", generator.generateEmployeeId());
        return user;
    }
}
