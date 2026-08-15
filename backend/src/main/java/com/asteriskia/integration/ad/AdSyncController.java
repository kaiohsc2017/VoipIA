package com.asteriskia.integration.ad;

import com.asteriskia.domain.accessgroup.AccessGroup;
import com.asteriskia.domain.accessgroup.AccessGroupRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * AdSyncController — status/disparo da sincronização, consulta ao espelho e mapeamento de grupos
 * AD → grupo de acesso. Tela provisória dentro de Configurações (Fase 1) — namespace RBAC
 * próprio (`callcenter.*`) só chega na Fase 2.
 */
@RestController
@RequestMapping("/api/v1/ad")
@RequiredArgsConstructor
public class AdSyncController {

    private final AdSyncScheduler syncScheduler;
    private final AdSyncRunRepository syncRunRepo;
    private final AdUserService adUserService;
    private final AdGroupMappingRepository groupMappingRepo;
    private final AccessGroupRepository accessGroupRepo;

    @GetMapping("/sync-status")
    public ResponseEntity<?> syncStatus() {
        return syncRunRepo
                .findFirstByOrderByStartedAtDesc()
                .map(run -> ResponseEntity.ok(SyncStatusResponse.from(run)))
                .orElse(ResponseEntity.ok(SyncStatusResponse.never()));
    }

    @PostMapping("/sync")
    public ResponseEntity<?> syncNow() {
        AdSyncRun run = syncScheduler.runSync();
        return ResponseEntity.ok(SyncStatusResponse.from(run));
    }

    @GetMapping("/users")
    public ResponseEntity<?> lookupUser(@RequestParam String query) {
        return adUserService
                .findMirrored(query)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/group-mappings")
    public ResponseEntity<List<GroupMappingResponse>> listGroupMappings() {
        return ResponseEntity.ok(
                groupMappingRepo.findAll().stream().map(GroupMappingResponse::from).toList());
    }

    @PostMapping("/group-mappings")
    public ResponseEntity<?> createGroupMapping(@Valid @RequestBody GroupMappingRequest req) {
        if (groupMappingRepo.findByAdGroupName(req.adGroupName()).isPresent()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Grupo AD já mapeado: " + req.adGroupName()));
        }
        AccessGroup group = accessGroupRepo.findById(req.accessGroupId()).orElse(null);
        if (group == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Grupo de acesso não existe"));
        }
        AdGroupMapping saved =
                groupMappingRepo.save(
                        AdGroupMapping.builder()
                                .adGroupName(req.adGroupName())
                                .accessGroup(group)
                                .build());
        return ResponseEntity.status(HttpStatus.CREATED).body(GroupMappingResponse.from(saved));
    }

    @DeleteMapping("/group-mappings/{id}")
    public ResponseEntity<Void> deleteGroupMapping(@PathVariable Long id) {
        if (!groupMappingRepo.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        groupMappingRepo.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    public record GroupMappingRequest(
            @NotBlank String adGroupName, @jakarta.validation.constraints.NotNull Integer accessGroupId) {}

    public record GroupMappingResponse(Long id, String adGroupName, Integer accessGroupId, String accessGroupName) {
        static GroupMappingResponse from(AdGroupMapping m) {
            return new GroupMappingResponse(
                    m.getId(), m.getAdGroupName(), m.getAccessGroup().getId(), m.getAccessGroup().getName());
        }
    }

    public record SyncStatusResponse(
            String status, String startedAt, String finishedAt, int usersSynced, String errorMessage) {
        static SyncStatusResponse from(AdSyncRun run) {
            return new SyncStatusResponse(
                    run.getStatus().name(),
                    String.valueOf(run.getStartedAt()),
                    run.getFinishedAt() != null ? String.valueOf(run.getFinishedAt()) : null,
                    run.getUsersSynced(),
                    run.getErrorMessage());
        }

        static SyncStatusResponse never() {
            return new SyncStatusResponse("NEVER_RUN", null, null, 0, null);
        }
    }
}
