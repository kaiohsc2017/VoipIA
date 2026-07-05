package com.asteriskia.domain.connectivity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface NumberTestRepository extends JpaRepository<NumberTest, Long> {
    List<NumberTest> findByIsActive(Boolean isActive);

    Optional<NumberTest> findByPhoneNumberSourceId(Long phoneNumberId);
}
