package com.kibo.reservation.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.kibo.reservation.config.DataSeeder;
import com.kibo.reservation.domain.Drop;
import com.kibo.reservation.repository.DropRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/** Seed data against the real schema: valid rows, and never duplicated on restart. */
@TestPropertySource(properties = "kibo.seed.enabled=true")
class DataSeederIT extends AbstractMySqlIT {

    @Autowired
    private DataSeeder seeder;

    @Autowired
    private DropRepository drops;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void seedsOnceIntoAnEmptyDatabaseAndIsIdempotent() {
        jdbc.update("DELETE FROM holds");
        jdbc.update("DELETE FROM drops");

        seeder.run(new DefaultApplicationArguments());
        List<Drop> seeded = drops.findAllByOrderByStartsAtAscIdAsc();
        assertThat(seeded).hasSize(4);
        assertThat(seeded).allSatisfy(d -> assertThat(d.getAvailableQuantity()).isEqualTo(d.getTotalQuantity()));
        assertThat(seeded).extracting(Drop::getTotalQuantity).containsExactlyInAnyOrder(50, 5, 1, 20);

        // A restart (second run) must not add or reset anything.
        seeder.run(new DefaultApplicationArguments());
        assertThat(drops.count()).isEqualTo(4);
    }
}
