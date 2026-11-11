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

    /** Finalized or voided orders whose finalization falls in [from, to), newest first (today's orders list). */
    @Query("""
            select o from CustomerOrder o
            where o.status in :statuses and o.finalizedAt >= :from and o.finalizedAt < :to
            order by o.finalizedAt desc, o.id desc
            """)
    List<CustomerOrder> findFinishedBetween(@Param("statuses") Collection<OrderStatus> statuses,
                                            @Param("from") Instant from,
                                            @Param("to") Instant to);

    /** Reports: one lightweight row per order in [from, to); the service buckets them by Kigali day/week/month. */
    @Query("""
            select new com.epicit.pos.repo.SaleRow(o.finalizedAt, o.paymentMethod, o.total)
            from CustomerOrder o
            where o.status = :status and o.finalizedAt >= :from and o.finalizedAt < :to
            """)
    List<SaleRow> salesBetween(@Param("status") OrderStatus status,
                               @Param("from") Instant from,
                               @Param("to") Instant to);

    /** Reports: items sold in [from, to) for orders in the given status, best sellers first. */
    @Query("""
            select new com.epicit.pos.repo.ItemTotals(l.itemName, sum(l.qty), sum(l.qty * l.unitPrice))
            from OrderLine l
            where l.order.status = :status and l.order.finalizedAt >= :from and l.order.finalizedAt < :to
            group by l.itemName
            order by sum(l.qty) desc, l.itemName asc
            """)
    List<ItemTotals> itemTotalsBetween(@Param("status") OrderStatus status,
                                       @Param("from") Instant from,
                                       @Param("to") Instant to);

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
