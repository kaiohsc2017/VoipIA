package com.asteriskia.domain.datacenter;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PhoneNumberRepository extends JpaRepository<PhoneNumber, Long> {

    List<PhoneNumber> findByIsActive(Boolean isActive);

    List<PhoneNumber> findByClientIdOrderByCreatedAtDesc(Integer clientId);

    Optional<PhoneNumber> findByPhoneNumberAndNumberType(String phoneNumber, NumberType numberType);
}
