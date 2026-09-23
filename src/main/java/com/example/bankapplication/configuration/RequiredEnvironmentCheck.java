package com.example.bankapplication.configuration;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Spring Boot leaves an unresolved ${PLACEHOLDER} as literal text rather than failing, so a missing secret only
 * shows up later as a confusing downstream error (MySQL "Access denied", or every request getting a silent 401).
 * This fails at startup instead, with a message that says exactly what to set.
 * <p>
 * Replaces the earlier, single-purpose DbPasswordCheck now that two more secrets need the same treatment.
 */
public class RequiredEnvironmentCheck implements EnvironmentPostProcessor, Ordered {

    // property name -> (environment variable to set, one-line description of what it protects)
    private static final Map<String, String[]> REQUIRED = new LinkedHashMap<>();
    static {
        REQUIRED.put("spring.datasource.password", new String[]{"DB_PASSWORD", "the MySQL password"});
        REQUIRED.put("bank.admin.api-key", new String[]{"ADMIN_API_KEY", "the admin key bank.html uses for account-wide operations"});
        REQUIRED.put("bank.service.api-key", new String[]{"SERVICE_API_KEY", "the key trusted backend services (such as PhonepayService) use to call this app; it must match the SERVICE_API_KEY set on that service too"});
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        for (Map.Entry<String, String[]> entry : REQUIRED.entrySet()) {
            try {
                environment.getProperty(entry.getKey());
            } catch (IllegalArgumentException e) {
                String envVar = entry.getValue()[0];
                String description = entry.getValue()[1];
                throw new IllegalStateException(
                        entry.getKey() + " is not configured (" + description + "). Either set a " + envVar
                                + " environment variable (IntelliJ: Run > Edit Configurations > Environment variables), "
                                + "or add " + envVar + "=<value> to a .env file in the project root (copy .env.example).", e);
            }
        }
    }

    // Run after the application.properties files have been loaded.
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
