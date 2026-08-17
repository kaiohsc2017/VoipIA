package com.asteriskia.domain.callcenter.quality;

import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/callcenter/quality")
@RequiredArgsConstructor
public class CallCenterQualityCoachingController {

    private final CallCenterQualityCoachingService coachingService;

    @GetMapping("/appeals")
    public List<AppealView> listPendingAppeals() {
        return coachingService.listPendingAppeals();
    }

    @PutMapping("/appeals/{id}/review")
    public AppealView reviewAppeal(
            @PathVariable Long id,
            @Valid @RequestBody ReviewAppealRequest request,
            Principal principal) {
        String reviewer = principal != null ? principal.getName() : "SUPERVISOR";
        return coachingService.reviewAppeal(id, reviewer, request);
    }

    @PostMapping("/coaching")
    @ResponseStatus(HttpStatus.CREATED)
    public CoachingPlanView createCoachingPlan(
            @Valid @RequestBody CreateCoachingPlanRequest request,
            Principal principal) {
        String creator = principal != null ? principal.getName() : "SUPERVISOR";
        return coachingService.createCoachingPlan(creator, request);
    }
}
