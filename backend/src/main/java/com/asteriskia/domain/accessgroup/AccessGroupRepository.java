package com.asteriskia.domain.accessgroup;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AccessGroupRepository extends JpaRepository<AccessGroup, Integer> {
    Optional<AccessGroup> findByName(String name);
}
