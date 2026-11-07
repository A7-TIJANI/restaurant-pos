package com.epicit.pos.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.epicit.pos.IntegrationTest;
import com.epicit.pos.repo.AddonRepository;
import com.epicit.pos.repo.AuditLogRepository;
import com.epicit.pos.repo.MenuItemRepository;
import com.epicit.pos.repo.OrderRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.repository.CrudRepository;

@IntegrationTest
class OrderPersistenceTest {

    @Autowired OrderRepository orders;
    @Autowired MenuItemRepository menuItems;
    @Autowired AddonRepository addons;
    @Autowired AuditLogRepository audit;
    @Autowired EntityManager em;

    private MenuItem meatKofta() {
        return menuItems.findByActiveTrueOrderByIdAsc().get(0);
    }

    @Test
    void orderWithLinesAndAddonsRoundTrips() {
        CustomerOrder order = new CustomerOrder(orders.maxOrderNumber() + 1);
        OrderLine line = order.addLine(meatKofta(), 2);
        line.addAddon(addons.findByActiveTrueOrderByIdAsc().get(0));
        order.setTotal(order.computeTotal());
        orders.saveAndFlush(order);
        em.clear();

        CustomerOrder loaded = orders.findByOrderNumber(1).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(OrderStatus.DRAFT);
        assertThat(loaded.getTotal()).isEqualTo(5000L);
        assertThat(loaded.getLines()).hasSize(1);
        assertThat(loaded.getLines().get(0).getAddons()).extracting(OrderLineAddon::getAddonName)
                .containsExactly("Onion");
    }

    @Test
    void menuEditsNeverRewritePastOrders() {
        MenuItem item = meatKofta();
        CustomerOrder order = new CustomerOrder(orders.maxOrderNumber() + 1);
        order.addLine(item, 1);
        orders.saveAndFlush(order);

        item.setName("Beef Kofta");
        item.setPrice(9999);
        menuItems.saveAndFlush(item);
        em.clear();

        OrderLine line = orders.findByOrderNumber(1).orElseThrow().getLines().get(0);
        assertThat(line.getItemName()).isEqualTo("Meat Kofta");
        assertThat(line.getUnitPrice()).isEqualTo(2500L);
    }

    @Test
    void orderNumbersAreUnique() {
        orders.saveAndFlush(new CustomerOrder(7));
        assertThatThrownBy(() -> orders.saveAndFlush(new CustomerOrder(7)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deactivatedItemsLeaveTheGridButStayReferencedByHistory() {
        MenuItem item = meatKofta();
        CustomerOrder order = new CustomerOrder(orders.maxOrderNumber() + 1);
        order.addLine(item, 1);
        orders.saveAndFlush(order);

        item.setActive(false);
        menuItems.saveAndFlush(item);
        em.clear();

        assertThat(menuItems.findByActiveTrueOrderByIdAsc()).extracting(MenuItem::getName)
                .doesNotContain("Meat Kofta");
        assertThat(orders.findByOrderNumber(1).orElseThrow().getLines().get(0).getMenuItem().getId())
                .isEqualTo(item.getId());
    }

    @Test
    void auditLogIsAppendOnlyByType() {
        long before = audit.count();
        audit.save(new AuditLog("ORDER_VOID", "order:1", "FINALIZED", "VOIDED", "customer left"));
        assertThat(audit.count()).isEqualTo(before + 1);
        // No delete/update methods are even reachable: the repository is not a CrudRepository.
        assertThat(CrudRepository.class.isAssignableFrom(AuditLogRepository.class)).isFalse();
    }

    @Test
    void stockFlagsFollowTheThreshold() {
        MenuItem item = meatKofta();
        item.setStock(0);
        assertThat(item.isOutOfStock()).isTrue();
        assertThat(item.isLowStock()).isFalse();
        item.setStock(5);
        assertThat(item.isLowStock()).isTrue();
        item.setStock(6);
        assertThat(item.isLowStock()).isFalse();
    }
}
