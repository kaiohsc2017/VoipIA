package com.asteriskia.domain.accessgroup;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/** Chave composta (group_id, resource_key) de {@link AccessGroupPermission}. */
@EqualsAndHashCode
@NoArgsConstructor
@AllArgsConstructor
public class AccessGroupPermissionId implements Serializable {
    private Integer group;
    private String resourceKey;
}
