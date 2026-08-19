package com.asteriskia.domain.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * AppUserRepository — Repositório JPA para usuários do sistema VoipIA.
 * Precisa ser público para injeção no AuthController (package config).
 */
@Repository
public interface AppUserRepository extends JpaRepository<AppUser, Integer> {

    Optional<AppUser> findByUsername(String username);

    Optional<AppUser> findByUsernameIgnoreCase(String username);

    Optional<AppUser> findByUsernameAndIsActiveTrue(String username);

    Optional<AppUser> findByUsernameIgnoreCaseAndIsActiveTrue(String username);

    /**
     * Retorna o próximo ramal disponível a partir de {@code start}.
     * Busca o menor inteiro em [start, 9099] que não esteja em uso.
     * Fallback: MAX(extension) + 1 se todos estiverem ocupados em sequência.
     */
    @Query(value = """
        SELECT COALESCE(
            (SELECT s.ext FROM generate_series(:start, 9099) AS s(ext)
             WHERE s.ext NOT IN (SELECT extension FROM app_users) LIMIT 1),
            (SELECT MAX(extension) + 1 FROM app_users WHERE extension >= :start)
        )
        """, nativeQuery = true)
    int findNextExtension(int start);
}
