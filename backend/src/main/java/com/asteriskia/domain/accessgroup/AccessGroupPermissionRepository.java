package com.asteriskia.domain.accessgroup;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AccessGroupPermissionRepository extends JpaRepository<AccessGroupPermission, AccessGroupPermissionId> {
    List<AccessGroupPermission> findByGroupId(Integer groupId);
}
