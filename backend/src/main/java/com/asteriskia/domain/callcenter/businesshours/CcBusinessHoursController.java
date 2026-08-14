package com.asteriskia.domain.callcenter.businesshours;

import com.asteriskia.domain.callcenter.businesshours.BusinessHoursDtos.CalendarRequest;
import com.asteriskia.domain.callcenter.businesshours.BusinessHoursDtos.CalendarView;
import com.asteriskia.domain.callcenter.businesshours.BusinessHoursDtos.SlotRequest;
import com.asteriskia.domain.callcenter.businesshours.BusinessHoursDtos.SlotView;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CcBusinessHoursController — CRUD de calendário/slots de horário de funcionamento (Fase 5e.1,
 * V74). RBAC: {@code callcenter.config} (mesmo resource dos ranges/NPS da Fase 19 e das
 * tabulações/motivos de pausa da Fase 12.6, ver {@code SecurityConfig}).
 */
@RestController
@RequestMapping("/api/v1/callcenter/business-hours")
@RequiredArgsConstructor
public class CcBusinessHoursController {

    private final CcBusinessHoursService service;

    @GetMapping
    public ResponseEntity<List<CalendarView>> list() {
        return ResponseEntity.ok(service.list());
    }

    @GetMapping("/{id}")
    public ResponseEntity<CalendarView> getById(@PathVariable Long id) {
        return ResponseEntity.ok(service.getById(id));
    }

    @PostMapping
    public ResponseEntity<CalendarView> create(@Valid @RequestBody CalendarRequest request) {
        return ResponseEntity.ok(service.create(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<CalendarView> update(@PathVariable Long id, @Valid @RequestBody CalendarRequest request) {
        return ResponseEntity.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/slots")
    public ResponseEntity<SlotView> addSlot(@PathVariable Long id, @Valid @RequestBody SlotRequest request) {
        return ResponseEntity.ok(service.addSlot(id, request));
    }

    @DeleteMapping("/{id}/slots/{slotId}")
    public ResponseEntity<Void> removeSlot(@PathVariable Long id, @PathVariable Long slotId) {
        service.removeSlot(id, slotId);
        return ResponseEntity.noContent().build();
    }
}
