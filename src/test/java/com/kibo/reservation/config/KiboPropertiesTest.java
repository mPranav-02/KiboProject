package com.kibo.reservation.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Startup validation of {@code kibo.*}: a bad business setting must fail fast, not as a 500 per request. */
class KiboPropertiesTest {

    private static jakarta.validation.ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    @Test
    void theDefaultsAreValid() {
        assertThat(validator.validate(properties(Duration.ofMinutes(5)))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PT0S", "-PT1M"})
    void aHoldDurationThatIsNotPositiveIsRejected(String duration) {
        Set<ConstraintViolation<KiboProperties>> violations = validator.validate(properties(Duration.parse(duration)));

        assertThat(violations).singleElement().satisfies(violation -> {
            assertThat(violation.getPropertyPath()).hasToString("hold.durationPositive");
            assertThat(violation.getMessage()).isEqualTo("must be a positive duration");
        });
    }

    private static KiboProperties properties(Duration holdDuration) {
        return new KiboProperties(
                new KiboProperties.HoldSettings(holdDuration, 4),
                new KiboProperties.ExpirationSettings(Duration.ofSeconds(2), 200),
                new KiboProperties.CacheSettings(Duration.ofSeconds(3)),
                new KiboProperties.SeedSettings(true));
    }
}
