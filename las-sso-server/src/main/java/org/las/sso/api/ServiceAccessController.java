package org.las.sso.api;

import java.util.UUID;

import org.las.sso.access.ServiceAccessEligibilityService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/principals")
public final class ServiceAccessController {

    private final ServiceAccessEligibilityService eligibility;
    private final org.las.sso.observability.SsoMetrics metrics;

    public ServiceAccessController(ServiceAccessEligibilityService eligibility, org.las.sso.observability.SsoMetrics metrics) {
        this.eligibility = eligibility;
        this.metrics = metrics;
    }

    @GetMapping("/{subject}/service-access")
    ResponseEntity<ServiceAccessEligibilityService.Decision> serviceAccess(
            @PathVariable UUID subject) {
        final ServiceAccessEligibilityService.Decision decision;
        try {
            decision = eligibility.evaluate(subject);
        } catch (org.springframework.dao.DataAccessException unavailable) {
            metrics.serviceAccess(org.las.sso.observability.SsoMetrics.AccessDecision.UNAVAILABLE);
            throw unavailable;
        }
        metrics.serviceAccess("ALLOWED".equals(decision.decision())
                ? org.las.sso.observability.SsoMetrics.AccessDecision.ALLOWED
                : org.las.sso.observability.SsoMetrics.AccessDecision.DENIED);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(decision);
    }
}
