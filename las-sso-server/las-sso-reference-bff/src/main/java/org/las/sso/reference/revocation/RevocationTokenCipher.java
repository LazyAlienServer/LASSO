package org.las.sso.reference.revocation;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class RevocationTokenCipher {
    private final String keyId;
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    RevocationTokenCipher(String keyId, String base64Key) {
        if (keyId == null || !keyId.matches("[A-Za-z0-9._-]{1,64}"))
            throw new IllegalArgumentException("Invalid revocation key identifier");
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64Key);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid revocation encryption key");
        }
        try {
            if (bytes.length != 32)
                throw new IllegalArgumentException("Revocation encryption requires a 256-bit key");
            this.keyId = keyId;
            this.key = new SecretKeySpec(bytes, "AES");
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    String keyId() {
        return keyId;
    }

    byte[] verifier() {
        byte[] encoded = key.getEncoded();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(encoded, "HmacSHA256"));
            return mac.doFinal(
                    ("reference-revocation-key-registry-v1:" + keyId)
                            .getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Revocation key verification unavailable");
        } finally {
            Arrays.fill(encoded, (byte) 0);
        }
    }

    boolean sameMaterial(RevocationTokenCipher other) {
        byte[] current = key.getEncoded(), next = other.key.getEncoded();
        try {
            return java.security.MessageDigest.isEqual(current, next);
        } finally {
            Arrays.fill(current, (byte) 0);
            Arrays.fill(next, (byte) 0);
        }
    }

    RevocationTask encrypt(RevocationTask task, String token) {
        byte[] nonce = new byte[12];
        random.nextBytes(nonce);
        byte[] plain = token.getBytes(StandardCharsets.UTF_8);
        try {
            return new RevocationTask(
                    task.id(),
                    task.registrationId(),
                    task.issuer(),
                    task.clientId(),
                    task.uri(),
                    task.hint(),
                    keyId,
                    nonce,
                    crypt(Cipher.ENCRYPT_MODE, task, nonce, plain),
                    null,
                    0);
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    String decrypt(RevocationTask task) {
        if (!keyId.equals(task.keyId()))
            throw new IllegalStateException("Revocation encryption key unavailable");
        byte[] plain = crypt(Cipher.DECRYPT_MODE, task, task.nonce(), task.ciphertext());
        try {
            return new String(plain, StandardCharsets.UTF_8);
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    private byte[] crypt(int mode, RevocationTask task, byte[] nonce, byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad(task));
            return cipher.doFinal(input);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Revocation payload unavailable");
        }
    }

    private byte[] aad(RevocationTask task) {
        try {
            var bytes = new ByteArrayOutputStream();
            var output = new DataOutputStream(bytes);
            for (String value :
                    new String[] {
                        "reference-revocation-v1",
                        task.id().toString(),
                        task.registrationId(),
                        task.issuer(),
                        task.clientId(),
                        task.uri(),
                        task.hint(),
                        keyId
                    }) {
                byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
                output.writeInt(encoded.length);
                output.write(encoded);
            }
            return bytes.toByteArray();
        } catch (java.io.IOException impossible) {
            throw new IllegalStateException("Revocation metadata unavailable");
        }
    }
}
