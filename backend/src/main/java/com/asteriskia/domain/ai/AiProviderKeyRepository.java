package com.asteriskia.domain.ai;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AiProviderKeyRepository extends JpaRepository<AiProviderKey, String> {
    List<AiProviderKey> findByIsActiveTrue();
}
