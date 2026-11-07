package com.epicit.pos.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.epicit.pos.IntegrationTest;
import com.epicit.pos.repo.AddonRepository;
import com.epicit.pos.repo.MenuItemRepository;
import com.epicit.pos.repo.OrderRepository;
import com.epicit.pos.repo.SettingsRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Proves the Flyway migrations run, Hibernate validates against them, and the v1 defaults are seeded. */
@IntegrationTest
class SchemaAndSeedTest {

    @Autowired MenuItemRepository menuItems;
    @Autowired AddonRepository addons;
    @Autowired SettingsRepository settings;
    @Autowired OrderRepository orders;

    @Test
    void seedsTheThreeV1MenuItems() {
        assertThat(menuItems.findByActiveTrueOrderByIdAsc())
                .extracting(MenuItem::getName, MenuItem::getPrice)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("Meat Kofta", 2500L),
                        org.assertj.core.groups.Tuple.tuple("Chicken Kofta", 2000L),
                        org.assertj.core.groups.Tuple.tuple("Fateera", 1500L));
    }

    @Test
    void seedsTheTenV1Addons() {
        assertThat(addons.findByActiveTrueOrderByIdAsc()).hasSize(10)
                .extracting(Addon::getName)
                .contains("Onion", "No Onion", "TAKE WAY");
    }

    @Test
    void settingsRowExistsWithoutAPinSoFirstRunWizardIsRequired() {
        Settings s = settings.findById(Settings.SINGLETON_ID).orElseThrow();
        assertThat(s.getOwnerPinHash()).isNull();
        assertThat(s.getFailedPinAttempts()).isZero();
        assertThat(s.getPinLockedUntil()).isNull();
    }

    @Test
    void firstOrderNumberIsOneAndNoOrdersExistYet() {
        assertThat(orders.maxOrderNumber()).isZero();
    }
}
