package org.las.sso.reference.revocation;

import java.net.URI;
import java.util.UUID;

import org.las.sso.client.LasSsoRevocationTaskSink;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

/** A normal return attests committed encrypted acceptance, never remote revocation success. */
final class JdbcRevocationQueue implements LasSsoRevocationTaskSink {
    private final JdbcRevocationStore store;
    private final RevocationTokenCipher cipher;
    private final boolean allowLoopbackHttp;

    JdbcRevocationQueue(JdbcRevocationStore store, RevocationTokenCipher cipher, boolean allowLoopbackHttp) {
        this.store = store;
        this.cipher = cipher;
        this.allowLoopbackHttp = allowLoopbackHttp;
    }

    @Override public void submit(ClientRegistration registration, String token, String hint) {
        try {
            if (token == null || token.isBlank() || token.length() > 16384
                    || !("refresh_token".equals(hint) || "access_token".equals(hint))) {
                throw new IllegalArgumentException("Invalid revocation request");
            }
            String uri = endpoint(registration, allowLoopbackHttp);
            var task = new RevocationTask(UUID.randomUUID(), registration.getRegistrationId(),
                    registration.getProviderDetails().getIssuerUri(), registration.getClientId(), uri,
                    hint, null, null, null, null, 0);
            store.insert(cipher.encrypt(task, token));
        } catch (RuntimeException exception) {
            // JDBC/registration exception text may contain SQL bind values, secrets or endpoint text.
            throw new IllegalStateException("Durable revocation acceptance failed");
        }
    }

    static String endpoint(ClientRegistration registration, boolean allowLoopbackHttp) {
        String issuer = registration.getProviderDetails().getIssuerUri();
        Object advertised = registration.getProviderDetails().getConfigurationMetadata().get("revocation_endpoint");
        if (issuer == null || issuer.isBlank() || registration.getClientId().isBlank()
                || !ClientAuthenticationMethod.CLIENT_SECRET_BASIC.equals(registration.getClientAuthenticationMethod())
                || registration.getClientSecret() == null || registration.getClientSecret().isBlank()
                || !(advertised instanceof String text)) {
            throw new IllegalArgumentException("Confidential client revocation configuration required");
        }
        URI uri = URI.create(text);
        boolean loopback = "127.0.0.1".equals(uri.getHost()) || "[::1]".equals(uri.getHost());
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getFragment() != null
                || !("https".equals(uri.getScheme()) || (allowLoopbackHttp && loopback && "http".equals(uri.getScheme())))) {
            throw new IllegalArgumentException("Explicit HTTPS revocation endpoint required");
        }
        return text;
    }
}
