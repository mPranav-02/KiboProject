package com.kibo.reservation.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kibo.reservation.domain.Drop;
import com.kibo.reservation.domain.DropAvailabilityStatus;
import com.kibo.reservation.repository.DropRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;

@ExtendWith(MockitoExtension.class)
class DataSeederTest {

    private static final Instant NOW = Instant.parse("2026-10-06T10:00:00Z");

    @Mock
    private DropRepository drops;

    @Test
    @SuppressWarnings("unchecked")
    void seedsFourDropsWhenEnabledAndTableIsEmpty() {
        when(drops.count()).thenReturn(0L);
        when(drops.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        seeder(true).run(new DefaultApplicationArguments());

        ArgumentCaptor<List<Drop>> saved = ArgumentCaptor.forClass(List.class);
        verify(drops).saveAll(saved.capture());
        List<Drop> seeded = saved.getValue();
        assertThat(seeded).extracting(Drop::getTotalQuantity).containsExactly(50, 5, 1, 20);
        assertThat(seeded).allSatisfy(d -> {
            assertThat(d.getAvailableQuantity()).isEqualTo(d.getTotalQuantity());
            assertThat(d.getMaxPerHold()).isEqualTo(4);
        });
        assertThat(seeded).extracting(d -> d.availabilityStatus(NOW)).containsExactly(
                DropAvailabilityStatus.OPEN, DropAvailabilityStatus.OPEN, DropAvailabilityStatus.OPEN,
                DropAvailabilityStatus.UPCOMING);
        assertThat(seeded.get(3).getStartsAt()).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
    }

    @Test
    void doesNothingWhenDropsAlreadyExist() {
        when(drops.count()).thenReturn(3L);
        seeder(true).run(new DefaultApplicationArguments());
        verify(drops, never()).saveAll(any());
    }

    @Test
    void doesNothingWhenDisabled() {
        seeder(false).run(new DefaultApplicationArguments());
        verify(drops, never()).count();
        verify(drops, never()).saveAll(any());
    }

    private DataSeeder seeder(boolean enabled) {
        KiboProperties properties = new KiboProperties(
                new KiboProperties.HoldSettings(Duration.ofMinutes(5), 4),
                new KiboProperties.ExpirationSettings(Duration.ofSeconds(2), 200),
                new KiboProperties.CacheSettings(Duration.ofSeconds(3)),
                new KiboProperties.SeedSettings(enabled));
        return new DataSeeder(drops, properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
