package com.asteriskia.domain.callcenter.reports;

import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * CallCenterCustomerProfileController — "Perfil do cliente" (Fase 27). Sub-rota de
 * {@code /api/v1/callcenter/reports}, RBAC herdado do matcher genérico já existente em
 * {@code SecurityConfig} ({@code callcenter.reports}).
 */
@RestController
@RequestMapping("/api/v1/callcenter/reports/customer-profile")
@RequiredArgsConstructor
public class CallCenterCustomerProfileController {

    private final CallCenterCustomerProfileService service;

    @GetMapping
    public ResponseEntity<Page<CustomerProfileSummaryRow>> search(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), clampPageSize(size));
        return ResponseEntity.ok(service.search(from, to, pageable));
    }

    @GetMapping("/detail")
    public ResponseEntity<CustomerProfileDetail> detail(
            @RequestParam String contact,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(service.detail(contact, from, to));
    }

    private int clampPageSize(int size) {
        return Math.min(Math.max(size, 1), 100);
    }
}
