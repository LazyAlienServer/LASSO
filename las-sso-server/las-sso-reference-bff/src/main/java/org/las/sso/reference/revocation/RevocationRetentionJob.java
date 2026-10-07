package org.las.sso.reference.revocation;

import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

final class RevocationRetentionJob {
    private final JdbcRevocationStore store;
    private final RevocationProperties properties;

    RevocationRetentionJob(JdbcRevocationStore store, RevocationProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    @Scheduled(initialDelayString = "PT1H", fixedDelayString = "PT1H")
    void purge() {
        try {
            store.purgeCompleted(
                    properties.completedRetention(),
                    properties.cleanupBatchSize(),
                    "retention-job",
                    "COMPLETED_RECEIPT_RETENTION");
        } catch (RuntimeException exception) {
            LoggerFactory.getLogger(RevocationRetentionJob.class)
                    .error(
                            "Revocation retention unavailable: errorCode=STORAGE_OR_KEY_UNAVAILABLE");
        }
    }
}
