package org.las.sso.admin;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminAuditController {

    private final AdminAuditQueryService auditEvents;

    public AdminAuditController(AdminAuditQueryService auditEvents) {
        this.auditEvents = auditEvents;
    }

    @GetMapping("/audit-events")
    ResponseEntity<AdminAuditQueryService.AuditPage> auditEvents(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String targetId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(auditEvents.auditEvents(action, targetId, from, to, cursor, limit));
    }

    @GetMapping("/security-events")
    ResponseEntity<AdminAuditQueryService.SecurityEventPage> securityEvents(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(auditEvents.securityEvents(type, severity, userId, from, to, cursor, limit));
    }
}
