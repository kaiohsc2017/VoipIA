package com.asteriskia.domain.masterdata;

import com.asteriskia.domain.audit.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SegmentController — CRUD de Segmentos de negócio (Módulo 2), extraído de MasterDataController
 * (fase 10 da refatoração). Registra criações, atualizações e remoções no AuditLog.
 */
@RestController
@RequestMapping("/api/v1/segments")
@RequiredArgsConstructor
@Transactional
public class SegmentController {

    private final SegmentRepository segRepo;
    private final AuditService auditService;

    @GetMapping
    public ResponseEntity<List<Segment>> listSegments(
            @RequestParam(required = false) Boolean active) {
        List<Segment> result = active != null ? segRepo.findByIsActive(active) : segRepo.findAll();
        return ResponseEntity.ok(result);
    }

    @PostMapping
    public ResponseEntity<Segment> createSegment(
            @Valid @RequestBody Segment seg, HttpServletRequest req) {
        Segment saved = segRepo.save(seg);
        auditService.log(
                req,
                "MASTERDATA_CREATE",
                "Segmento criado: '" + saved.getName() + "' (id=" + saved.getId() + ")",
                true);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Segment> updateSegment(
            @PathVariable Integer id, @Valid @RequestBody Segment seg, HttpServletRequest req) {
        seg.setId(id);
        Segment saved = segRepo.save(seg);
        auditService.log(
                req,
                "MASTERDATA_UPDATE",
                "Segmento atualizado: '" + saved.getName() + "' (id=" + id + ")",
                true);
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteSegment(@PathVariable Integer id, HttpServletRequest req) {
        auditService.log(req, "MASTERDATA_DELETE", "Segmento removido (id=" + id + ")", true);
        segRepo.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
