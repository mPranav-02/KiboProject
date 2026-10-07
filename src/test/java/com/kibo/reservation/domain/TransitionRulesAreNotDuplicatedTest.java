package com.kibo.reservation.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.kibo.reservation.repository.HoldRepository;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

/**
 * Constitution VI: the transition rules live in {@link HoldStatus} only. No repository query may hard-code a
 * hold status; the guarded UPDATEs take their legal source states (and the target) as parameters supplied
 * from the table, so changing the table changes every transition in one place.
 */
class TransitionRulesAreNotDuplicatedTest {

    @Test
    void noHoldRepositoryQueryHardCodesAStatus() {
        List<String> offenders = new ArrayList<>();
        for (Method method : HoldRepository.class.getDeclaredMethods()) {
            Query query = method.getAnnotation(Query.class);
            if (query == null) {
                continue;
            }
            for (HoldStatus status : HoldStatus.values()) {
                if (query.value().contains("HoldStatus." + status.name()) || query.value().contains("'" + status.name() + "'")) {
                    offenders.add(method.getName() + " mentions " + status);
                }
            }
        }
        assertThat(offenders).isEmpty();
    }

    @Test
    void everyStatusChangingQueryIsGuardedByAParameterisedSourceSet() {
        for (Method method : HoldRepository.class.getDeclaredMethods()) {
            Query query = method.getAnnotation(Query.class);
            if (query != null && query.value().contains("UPDATE Hold")) {
                assertThat(query.value()).as(method.getName()).contains("h.status IN :sources");
            }
        }
    }
}
