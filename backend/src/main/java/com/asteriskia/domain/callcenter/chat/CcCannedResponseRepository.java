package com.asteriskia.domain.callcenter.chat;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CcCannedResponseRepository extends JpaRepository<CcCannedResponse, Long> {

    List<CcCannedResponse> findByActiveTrueOrderByTitleAsc();
}
