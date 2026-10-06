package org.las.sso.account;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.las.sso.config.RecoveryProperties;
import org.springframework.stereotype.Service;

@Service
public final class RecoveryCodeService {

    private static final char[] ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
    private static final int CODE_COUNT = 10;

    private final SecureRandom random = new SecureRandom();
    private final byte[] pepper;

    public RecoveryCodeService(RecoveryProperties properties) {
        this.pepper = properties.pepper().getBytes(StandardCharsets.UTF_8);
    }

    public List<RecoveryCode> generateBatch() {
        var codes = new ArrayList<RecoveryCode>(CODE_COUNT);
        for (int index = 0; index < CODE_COUNT; index++) {
            // Four groups of four uniformly selected 32-alphabet symbols: 16 * 5 = 80 random bits.
            String plaintext = "LAS-" + group() + "-" + group() + "-" + group() + "-" + group();
            codes.add(new RecoveryCode(plaintext, digest(plaintext)));
        }
        return List.copyOf(codes);
    }

    public RecoveryCode generateAdminCredential() {
        String plaintext = "LAS-ADMIN-" + group() + "-" + group() + "-" + group() + "-" + group();
        return new RecoveryCode(plaintext, digest(plaintext));
    }

    byte[] digest(String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
            return mac.doFinal(normalize(code).getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HmacSHA256 is unavailable", exception);
        }
    }

    private String group() {
        char[] value = new char[4];
        for (int index = 0; index < value.length; index++) {
            value[index] = ALPHABET[random.nextInt(ALPHABET.length)];
        }
        return new String(value);
    }

    private static String normalize(String code) {
        return code.replace("-", "").toUpperCase(java.util.Locale.ROOT);
    }

    public record RecoveryCode(String plaintext, byte[] digest) {
        public RecoveryCode {
            digest = digest.clone();
        }

        @Override
        public byte[] digest() {
            return digest.clone();
        }
    }
}
