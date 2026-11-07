package com.epicit.pos.repo;

import com.epicit.pos.domain.CustomerOrder;
import com.epicit.pos.domain.OrderStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OrderRepository extends JpaRepository<CustomerOrder, Long> {

    /** Highest business number used so far (0 when none). Numbers are never reset and voids keep theirs. */
    @Query("select coalesce(max(o.orderNumber), 0) from CustomerOrder o")
    int maxOrderNumber();

    Optional<CustomerOrder> findByOrderNumber(int orderNumber);

    List<CustomerOrder> findByStatus(OrderStatus status);
}
