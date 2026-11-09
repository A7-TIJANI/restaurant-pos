package com.epicit.pos.repo;

import com.epicit.pos.domain.CustomerOrder;
import com.epicit.pos.domain.OrderStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<CustomerOrder, Long> {

    /** Highest business number used so far (0 when none). Numbers are never reset and voids keep theirs. */
    @Query("select coalesce(max(o.orderNumber), 0) from CustomerOrder o")
    int maxOrderNumber();

    Optional<CustomerOrder> findByOrderNumber(int orderNumber);

    List<CustomerOrder> findByStatus(OrderStatus status);

    /** The one in-progress order (DRAFT or a PRINTING_* state), newest first as a safety net (A12). */
    Optional<CustomerOrder> findFirstByStatusInOrderByIdDesc(Collection<OrderStatus> statuses);

    /** Orders in the given status finalized within [dayStart, dayEnd), grouped by payment method. */
    @Query("""
            select new com.epicit.pos.repo.PaymentTotals(o.paymentMethod, count(o), coalesce(sum(o.total), 0L))
            from CustomerOrder o
            where o.status = :status and o.finalizedAt >= :dayStart and o.finalizedAt < :dayEnd
            group by o.paymentMethod
            """)
    List<PaymentTotals> totalsByPayment(@Param("status") OrderStatus status,
                                        @Param("dayStart") Instant dayStart,
                                        @Param("dayEnd") Instant dayEnd);
}
