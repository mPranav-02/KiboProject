package com.kibo.reservation.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.kibo.reservation.application.HoldExpirationJob;
import com.kibo.reservation.application.HoldExpirationService;
import com.kibo.reservation.application.HoldService;
import com.kibo.reservation.repository.DropRepository;
import com.kibo.reservation.repository.HoldRepository;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Guards the rule "Redis is a cache, never an input to a decision" (Constitution V) structurally: nothing
 * on the hold path, and no repository, can even see the cache or Redis.
 */
class CacheIsNotUsedForDecisionsTest {

    @Test
    void theHoldPathAndRepositoriesHaveNoDependencyOnTheCacheOrRedis() {
        for (Class<?> type : List.of(HoldService.class, HoldExpirationService.class, HoldExpirationJob.class,
                HoldRepository.class, DropRepository.class)) {
            assertThat(dependenciesOf(type))
                    .as("types %s depends on", type.getSimpleName())
                    .noneMatch(t -> t.equals(DropCache.class.getName()))
                    .noneMatch(t -> t.startsWith("org.springframework.data.redis"))
                    .noneMatch(t -> t.startsWith("org.springframework.cache"));
        }
    }

    private static List<String> dependenciesOf(Class<?> type) {
        List<String> names = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            names.add(field.getType().getName());
        }
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            for (Class<?> parameter : constructor.getParameterTypes()) {
                names.add(parameter.getName());
            }
        }
        return names;
    }
}
