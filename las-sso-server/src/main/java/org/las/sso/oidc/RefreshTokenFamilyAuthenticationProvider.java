package org.las.sso.oidc;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2RefreshTokenAuthenticationToken;
import org.springframework.transaction.support.TransactionOperations;

public final class RefreshTokenFamilyAuthenticationProvider implements AuthenticationProvider {

    private static final Object DENIED = new Object();

    private final AuthenticationProvider delegate;
    private final RefreshTokenFamilyService tokenFamilies;
    private final TransactionOperations transactions;
    private final org.las.sso.observability.SsoMetrics metrics;

    public RefreshTokenFamilyAuthenticationProvider(
            AuthenticationProvider delegate,
            RefreshTokenFamilyService tokenFamilies,
            TransactionOperations transactions,
            org.las.sso.observability.SsoMetrics metrics) {
        this.delegate = delegate;
        this.tokenFamilies = tokenFamilies;
        this.transactions = transactions;
        this.metrics = metrics;
    }

    @Override
    public Authentication authenticate(Authentication authentication)
            throws AuthenticationException {
        var refresh = (OAuth2RefreshTokenAuthenticationToken) authentication;
        final Object result;
        try {
            if (!(refresh.getPrincipal() instanceof OAuth2ClientAuthenticationToken client)
                    || !client.isAuthenticated()
                    || client.getRegisteredClient() == null) {
                throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_CLIENT);
            }
            result =
                    transactions.execute(
                            status -> {
                                if (!tokenFamilies.lockAndValidate(
                                        refresh.getRefreshToken(),
                                        client.getRegisteredClient().getId())) {
                                    return DENIED;
                                }
                                return delegate.authenticate(authentication);
                            });
        } catch (OAuth2AuthenticationException rejected) {
            metrics.refresh(org.las.sso.observability.SsoMetrics.RefreshResult.REJECTED);
            throw rejected;
        } catch (RuntimeException failure) {
            metrics.refresh(org.las.sso.observability.SsoMetrics.RefreshResult.ERROR);
            throw failure;
        }
        if (result == DENIED) {
            metrics.refresh(org.las.sso.observability.SsoMetrics.RefreshResult.REJECTED);
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_GRANT);
        }
        metrics.refresh(org.las.sso.observability.SsoMetrics.RefreshResult.SUCCESS);
        return (Authentication) result;
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return OAuth2RefreshTokenAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
