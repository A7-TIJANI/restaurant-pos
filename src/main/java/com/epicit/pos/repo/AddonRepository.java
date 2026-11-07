package com.epicit.pos.repo;

import com.epicit.pos.domain.Addon;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AddonRepository extends JpaRepository<Addon, Long> {

    List<Addon> findByActiveTrueOrderByIdAsc();
}
