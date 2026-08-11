package com.asteriskia.domain.ai;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AiModelPricingRepository extends JpaRepository<AiModelPricing, String> {}
