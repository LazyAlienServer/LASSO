package org.las.sso.admin;

import java.time.Clock;
import org.las.sso.access.ServiceAccessEligibilityService;
import org.las.sso.identity.UuidV7Generator;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Injected-clock fixture; callers explicitly wrap this real service in TransactionTemplate. */
public final class AdminDeletionClockFixture {
    private AdminDeletionClockFixture() {}

    public static AdminDeletionRequestService service(
            JdbcClient jdbc,
            UuidV7Generator ids,
            ServiceAccessEligibilityService eligibility,
            Clock clock) {
        return new AdminDeletionRequestService(jdbc, ids, eligibility, clock);
    }
}
