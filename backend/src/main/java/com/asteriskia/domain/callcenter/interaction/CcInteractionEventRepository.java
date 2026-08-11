package com.asteriskia.domain.callcenter.interaction;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CcInteractionEventRepository extends JpaRepository<CcInteractionEvent, Long> {}
