package com.asteriskia.domain.callcenter.businesshours;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CcBusinessHoursRepository extends JpaRepository<CcBusinessHours, Long> {

    List<CcBusinessHours> findAllByOrderByNameAsc();
}
