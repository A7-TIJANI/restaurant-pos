package com.epicit.pos.repo;

import com.epicit.pos.domain.Addon;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AddonRepository extends JpaRepository<Addon, Long> {

    List<Addon> findByActiveTrueOrderByIdAsc();

    /** Menu management (FR-6.2): every add-on, deactivated ones included. */
    List<Addon> findAllByOrderByIdAsc();

    boolean existsByActiveTrueAndNameIgnoreCaseAndIdNot(String name, Long id);
}
