package com.epicit.pos.repo;

import com.epicit.pos.domain.MenuItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MenuItemRepository extends JpaRepository<MenuItem, Long> {

    /** The POS grid: active items only, in stable creation order. */
    List<MenuItem> findByActiveTrueOrderByIdAsc();

    /** Menu management (FR-6): every item, deactivated ones included, in stable creation order. */
    List<MenuItem> findAllByOrderByIdAsc();

    boolean existsByActiveTrueAndNameIgnoreCaseAndIdNot(String name, Long id);

    /**
     * Restock / correction (FR-6.3) as one atomic UPDATE, so it can never overwrite a sale that finalizes at the same
     * moment (a read-modify-write would). Returns 0 if the item does not exist or the result would be negative.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update MenuItem m set m.stock = m.stock + :delta where m.id = :id and m.stock + :delta >= 0")
    int adjustStock(@Param("id") Long id, @Param("delta") int delta);

    /** Set stock to an exact counted number (FR-6.3 "set to"). Also one UPDATE, so it cannot clobber other columns. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update MenuItem m set m.stock = :stock where m.id = :id")
    int setStock(@Param("id") Long id, @Param("stock") int stock);
}
