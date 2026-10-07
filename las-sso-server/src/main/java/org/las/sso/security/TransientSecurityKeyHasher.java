package org.las.sso.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.las.sso.config.TransientSecurityProperties;
import org.springframework.stereotype.Component;

@Component
public final class TransientSecurityKeyHasher {

    private final byte[] pepper;

    public TransientSecurityKeyHasher(TransientSecurityProperties properties) {
        this.pepper = properties.keyPepper().getBytes(StandardCharsets.UTF_8);
    }

    public byte[] hash(String namespace, String value) {
        if (namespace == null || namespace.isBlank() || value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Transient security key requires namespace and value");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
            mac.update(namespace.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HmacSHA256 is unavailable", exception);
        }
    }
}
