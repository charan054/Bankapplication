package com.example.bankapplication.configuration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spring Boot does NOT fail on an unresolved ${VAR}; it silently keeps the literal text, which later surfaces as a
 * confusing downstream failure (MySQL "Access denied", or every request getting a silent 401). RequiredEnvironmentCheck
 * turns that into a clear startup error for each of the three secrets this app needs.
 * MockEnvironment is used (not StandardEnvironment) so values set on the developer's own machine can't affect these tests.
 */
class RequiredEnvironmentCheckTest {

    private final RequiredEnvironmentCheck check = new RequiredEnvironmentCheck();

    @ParameterizedTest
    @ValueSource(strings = {"spring.datasource.password", "bank.admin.api-key", "bank.service.api-key"})
    void unresolvedPlaceholder_failsStartupWithAClearMessage(String property) {
        MockEnvironment env = new MockEnvironment().withProperty(property, "${SOME_VAR}");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> check.postProcessEnvironment(env, null));

        assertTrue(ex.getMessage().contains(property), ex.getMessage());
        assertTrue(ex.getMessage().contains(".env"), "message should tell the developer about the .env file: " + ex.getMessage());
    }

    @Test
    void allThreeResolved_isAccepted() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.datasource.password", "s3cret")
                .withProperty("bank.admin.api-key", "admin-key-value")
                .withProperty("bank.service.api-key", "service-key-value");

        assertDoesNotThrow(() -> check.postProcessEnvironment(env, null));
    }

    @Test
    void emptyPassword_isAccepted_asUsedByTheTestProfile() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.datasource.password", "")
                .withProperty("bank.admin.api-key", "test-admin-key")
                .withProperty("bank.service.api-key", "test-service-key");

        assertDoesNotThrow(() -> check.postProcessEnvironment(env, null));
    }

    @Test
    void missingAdminKey_failsEvenIfThePasswordIsFine() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.datasource.password", "s3cret")
                .withProperty("bank.admin.api-key", "${ADMIN_API_KEY}")
                .withProperty("bank.service.api-key", "service-key-value");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> check.postProcessEnvironment(env, null));

        assertTrue(ex.getMessage().contains("ADMIN_API_KEY"), ex.getMessage());
    }

    @Test
    void noPropertiesAtAll_isAccepted() {
        assertDoesNotThrow(() -> check.postProcessEnvironment(new MockEnvironment(), null));
    }
}
