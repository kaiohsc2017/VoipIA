package com.asteriskia.domain.accessgroup;

import com.asteriskia.domain.audit.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * AccessGroupController — CRUD dos grupos de acesso (RBAC granular, V22).
 *
 * <p>GET /api/v1/access-groups → lista grupos com sua matriz de permissões GET
 * /api/v1/access-groups/{id} → busca um grupo POST /api/v1/access-groups → cria grupo custom +
 * matriz PUT /api/v1/access-groups/{id} → atualiza nome/descrição/matriz DELETE
 * /api/v1/access-groups/{id} → exclui (bloqueado para grupos de sistema)
 *
 * <p>Protegido por ROLE_ADMIN puro em SecurityConfig — gerenciar grupos não usa o próprio sistema
 * de permissões granulares (evita o ovo-e-galinha de um grupo customizado precisar de si mesmo pra
 * existir).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/access-groups")
@RequiredArgsConstructor
public class AccessGroupController {

    private final AccessGroupRepository groupRepo;
    private final AccessGroupPermissionRepository permissionRepo;
    private final AuditService auditService;

    @GetMapping
    public ResponseEntity<List<GroupResponse>> listGroups() {
        return ResponseEntity.ok(groupRepo.findAll().stream().map(this::toResponse).toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getGroup(@PathVariable Integer id) {
        return groupRepo
                .findById(id)
                .map(g -> ResponseEntity.ok(toResponse(g)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<?> createGroup(
            @Valid @RequestBody GroupRequest req, HttpServletRequest httpRequest) {
        if (groupRepo.findByName(req.name()).isPresent()) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("Já existe um grupo com esse nome: " + req.name()));
        }

        AccessGroup group =
                AccessGroup.builder()
                        .name(req.name())
                        .description(req.description())
                        .isSystem(false)
                        .build();
        AccessGroup saved = groupRepo.save(group);
        savePermissions(saved, req.permissions());

        auditService.log(
                httpRequest,
                "ACCESS_GROUP_CREATE",
                "Grupo de acesso criado: " + saved.getName(),
                true);
        log.info("Grupo de acesso criado: {}", saved.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(saved));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateGroup(
            @PathVariable Integer id,
            @Valid @RequestBody GroupRequest req,
            HttpServletRequest httpRequest) {
        return groupRepo
                .findById(id)
                .map(
                        group -> {
                            group.setName(req.name());
                            group.setDescription(req.description());
                            AccessGroup saved = groupRepo.save(group);
                            if (req.permissions() != null) {
                                permissionRepo.deleteAll(permissionRepo.findByGroupId(id));
                                savePermissions(saved, req.permissions());
                            }
                            auditService.log(
                                    httpRequest,
                                    "ACCESS_GROUP_UPDATE",
                                    "Grupo de acesso '" + saved.getName() + "' atualizado",
                                    true);
                            return ResponseEntity.ok(toResponse(saved));
                        })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteGroup(@PathVariable Integer id, HttpServletRequest httpRequest) {
        return groupRepo
                .findById(id)
                .map(
                        group -> {
                            if (Boolean.TRUE.equals(group.getIsSystem())) {
                                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                                        .body(
                                                new ErrorResponse(
                                                        "Grupos de sistema não podem ser excluídos: "
                                                                + group.getName()));
                            }
                            // FK app_users.access_group_id é RESTRICT — se houver usuário no
                            // grupo, o banco recusa a exclusão com uma constraint violation.
                            groupRepo.delete(group);
                            auditService.log(
                                    httpRequest,
                                    "ACCESS_GROUP_DELETE",
                                    "Grupo de acesso excluído: " + group.getName(),
                                    true);
                            log.info("Grupo de acesso excluído: {}", group.getName());
                            return ResponseEntity.noContent().build();
                        })
                .orElse(ResponseEntity.notFound().build());
    }

    // -----------------------------------------------------------------------

    private void savePermissions(AccessGroup group, List<PermissionEntry> entries) {
        if (entries == null) return;
        List<AccessGroupPermission> perms =
                entries.stream()
                        .map(
                                e ->
                                        AccessGroupPermission.builder()
                                                .group(group)
                                                .resourceKey(e.resourceKey())
                                                .canRead(Boolean.TRUE.equals(e.canRead()))
                                                .canWrite(Boolean.TRUE.equals(e.canWrite()))
                                                .build())
                        .toList();
        permissionRepo.saveAll(perms);
    }

    private GroupResponse toResponse(AccessGroup group) {
        List<PermissionEntry> perms =
                permissionRepo.findByGroupId(group.getId()).stream()
                        .map(
                                p ->
                                        new PermissionEntry(
                                                p.getResourceKey(),
                                                p.getCanRead(),
                                                p.getCanWrite()))
                        .toList();
        return new GroupResponse(
                group.getId(), group.getName(), group.getDescription(), group.getIsSystem(), perms);
    }
}
