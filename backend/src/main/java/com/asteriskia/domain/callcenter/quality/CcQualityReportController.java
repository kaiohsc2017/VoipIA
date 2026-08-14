package com.asteriskia.domain.callcenter.quality;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * CcQualityReportController — relatório de qualidade do Call Center (Fase 26). RBAC reusa
 * {@code callcenter.reports} (mesma aba "Relatórios" da 9a/9b/9c) — sem resource_key novo.
 * {@code /holidays} de escrita é {@code ROLE_ADMIN} puro (ver SecurityConfig), leitura fica sob
 * o mesmo {@code callcenter.reports}.
 */
@RestController
@RequestMapping("/api/v1/callcenter/quality-reports")
@RequiredArgsConstructor
public class CcQualityReportController {

    private final CcQualityReportService service;

    public record RequestReportBody(
            @NotNull QualityReportScopeType scopeType, String scopeValue,
            @NotNull LocalDate dateFrom, @NotNull LocalDate dateTo) {}

    public record HolidayRequest(@NotNull LocalDate date, @Size(max = 200) String description) {}

    @PostMapping
    public ResponseEntity<CcQualityReportDto> request(@jakarta.validation.Valid @RequestBody RequestReportBody body) {
        return ResponseEntity.ok(service.requestReport(
                body.scopeType(), body.scopeValue(), body.dateFrom(), body.dateTo(), currentUsername(), isAdmin()));
    }

    @GetMapping
    public ResponseEntity<Page<CcQualityReportDto>> list(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = PageRequest.of(page, Math.min(Math.max(size, 1), 100), Sort.by(Sort.Direction.DESC, "requestedAt"));
        return ResponseEntity.ok(service.list(pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<CcQualityReportDto> getById(@PathVariable Long id) {
        return service.getById(id).map(ResponseEntity::ok)
                .orElseThrow(() -> new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
    }

    @GetMapping("/next-allowed")
    public ResponseEntity<?> nextAllowed(
            @RequestParam QualityReportScopeType scopeType, @RequestParam(required = false) String scopeValue) {
        return ResponseEntity.ok(java.util.Map.of("nextAllowedAt",
                java.util.Optional.ofNullable(service.nextAllowed(scopeType, scopeValue))));
    }

    @GetMapping("/holidays")
    public ResponseEntity<List<CcHoliday>> listHolidays() {
        return ResponseEntity.ok(service.listHolidays());
    }

    @PostMapping("/holidays")
    public ResponseEntity<CcHoliday> createHoliday(@jakarta.validation.Valid @RequestBody HolidayRequest body) {
        return ResponseEntity.ok(service.createHoliday(body.date(), body.description()));
    }

    @DeleteMapping("/holidays/{id}")
    public ResponseEntity<Void> deleteHoliday(@PathVariable Long id) {
        service.deleteHoliday(id);
        return ResponseEntity.noContent().build();
    }

    private String currentUsername() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    private boolean isAdmin() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }
}
