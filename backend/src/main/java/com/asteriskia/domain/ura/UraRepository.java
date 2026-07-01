package com.asteriskia.domain.ura;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * UraRepository — Acesso a dados das URAs configuráveis.
 */
@Repository
public interface UraRepository extends JpaRepository<Ura, Integer> {
    Optional<Ura> findByExtension(String extension);
}
