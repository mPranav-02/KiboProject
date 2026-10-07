package com.kibo.reservation.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.kibo.reservation.api.DropController;
import com.kibo.reservation.api.HoldController;
import com.kibo.reservation.application.DropQueryService;
import com.kibo.reservation.application.HoldExpirationJob;
import com.kibo.reservation.application.HoldExpirationService;
import com.kibo.reservation.application.HoldService;
import com.kibo.reservation.cache.DropCache;
import com.kibo.reservation.domain.Drop;
import com.kibo.reservation.domain.Hold;
import com.kibo.reservation.domain.event.HoldLifecycleEvent;
import com.kibo.reservation.repository.DropRepository;
import com.kibo.reservation.repository.HoldRepository;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Guards "messaging is separate from business logic" structurally: nothing on the hold path, in the domain,
 * or in a repository can see the messaging package or the AMQP client. They only raise the in-process event.
 */
class MessagingIsolationTest {

    @Test
    void businessCodeHasNoDependencyOnMessagingOrAmqp() {
        for (Class<?> type : List.of(HoldService.class, HoldExpirationService.class, HoldExpirationJob.class,
                DropQueryService.class, DropCache.class, HoldController.class,
                DropController.class, HoldRepository.class, DropRepository.class, Drop.class, Hold.class,
                HoldLifecycleEvent.class)) {
            assertThat(dependenciesOf(type))
                    .as("types %s depends on", type.getSimpleName())
                    .noneMatch(t -> t.startsWith("com.kibo.reservation.messaging"))
                    .noneMatch(t -> t.startsWith("org.springframework.amqp"))
                    .noneMatch(t -> t.startsWith("com.rabbitmq"));
        }
    }

    @Test
    void thePublisherDependsOnlyOnTheEventAndTheBroker() {
        assertThat(dependenciesOf(RabbitHoldEventPublisher.class))
                .noneMatch(t -> t.startsWith("com.kibo.reservation.repository"))
                .noneMatch(t -> t.startsWith("com.kibo.reservation.application"))
                .noneMatch(t -> t.startsWith("org.springframework.transaction.PlatformTransactionManager"));
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
        for (Method method : type.getDeclaredMethods()) {
            names.add(method.getReturnType().getName());
            for (Class<?> parameter : method.getParameterTypes()) {
                names.add(parameter.getName());
            }
        }
        return names;
    }
}
