package com.asteriskia.domain.callcenter.ara;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PsEndpointRepository extends JpaRepository<PsEndpoint, String> {}
