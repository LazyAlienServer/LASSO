package org.las.sso.observability;

import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Retains the existing encoder, hash formats and upgrade policy; only durations/outcomes are
 * observed.
 */
public final class MeteredPasswordEncoder implements PasswordEncoder {

    private final PasswordEncoder delegate;
    private final SsoMetrics metrics;

    public MeteredPasswordEncoder(PasswordEncoder delegate, SsoMetrics metrics) {
        this.delegate = delegate;
        this.metrics = metrics;
    }

    @Override
    public String encode(CharSequence rawPassword) {
        long start = System.nanoTime();
        try {
            String encoded = delegate.encode(rawPassword);
            metrics.hash(
                    SsoMetrics.HashOperation.ENCODE,
                    algorithm(encoded),
                    SsoMetrics.HashResult.SUCCESS,
                    System.nanoTime() - start);
            return encoded;
        } catch (RuntimeException failure) {
            metrics.hash(
                    SsoMetrics.HashOperation.ENCODE,
                    SsoMetrics.HashAlgorithm.OTHER,
                    SsoMetrics.HashResult.ERROR,
                    System.nanoTime() - start);
            throw failure;
        }
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        long start = System.nanoTime();
        try {
            boolean matched = delegate.matches(rawPassword, encodedPassword);
            metrics.hash(
                    SsoMetrics.HashOperation.MATCH,
                    algorithm(encodedPassword),
                    matched ? SsoMetrics.HashResult.SUCCESS : SsoMetrics.HashResult.MISMATCH,
                    System.nanoTime() - start);
            return matched;
        } catch (RuntimeException failure) {
            metrics.hash(
                    SsoMetrics.HashOperation.MATCH,
                    algorithm(encodedPassword),
                    SsoMetrics.HashResult.ERROR,
                    System.nanoTime() - start);
            throw failure;
        }
    }

    @Override
    public boolean upgradeEncoding(String encodedPassword) {
        return delegate.upgradeEncoding(encodedPassword);
    }

    private static SsoMetrics.HashAlgorithm algorithm(String encoded) {
        if (encoded != null && encoded.startsWith("{argon2id}"))
            return SsoMetrics.HashAlgorithm.ARGON2ID;
        if (encoded != null && encoded.startsWith("{bcrypt}"))
            return SsoMetrics.HashAlgorithm.BCRYPT;
        return SsoMetrics.HashAlgorithm.OTHER;
    }
}
