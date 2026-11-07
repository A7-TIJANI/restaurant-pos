package com.epicit.pos.repo;

import com.epicit.pos.domain.MenuItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MenuItemRepository extends JpaRepository<MenuItem, Long> {

    /** The POS grid: active items only, in stable creation order. */
    List<MenuItem> findByActiveTrueOrderByIdAsc();
}
