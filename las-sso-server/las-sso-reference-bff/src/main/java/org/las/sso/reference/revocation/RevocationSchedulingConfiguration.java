package org.las.sso.reference.revocation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@Profile("durable-revocation")
@ConditionalOnProperty(
        prefix = "reference.revocation",
        name = "worker-enabled",
        havingValue = "true",
        matchIfMissing = true)
@EnableScheduling
class RevocationSchedulingConfiguration {}
