package org.las.sso.reference.revocation;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

@Configuration(proxyBeanMethods = false)
@Profile("durable-revocation & !revocation-maintenance")
@Import(RevocationStorageConfiguration.class)
class DurableRevocationConfiguration {
    @Bean
    JdbcRevocationQueue referenceRevocationQueue(
            JdbcRevocationStore store,
            RevocationTokenCipher cipher,
            RevocationProperties properties) {
        return new JdbcRevocationQueue(store, cipher, properties.allowLoopbackHttp());
    }

    @Bean
    RevocationRetentionJob referenceRevocationRetention(
            JdbcRevocationStore store, RevocationProperties properties) {
        return new RevocationRetentionJob(store, properties);
    }

    @Bean
    RevocationWorker referenceRevocationWorker(
            JdbcRevocationStore store,
            RevocationTokenCipher cipher,
            ClientRegistrationRepository registrations,
            RevocationProperties properties) {
        return new RevocationWorker(store, cipher, registrations, properties.allowLoopbackHttp());
    }
}
