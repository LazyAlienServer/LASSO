package org.las.sso.oidc;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2RefreshTokenAuthenticationToken;
import org.springframework.transaction.support.TransactionOperations;

public final class RefreshTokenFamilyAuthenticationProvider implements AuthenticationProvider {

    private static final Object DENIED = new Object();

    private final AuthenticationProvider delegate;
    private final RefreshTokenFamilyService tokenFamilies;
    private final TransactionOperations transactions;

    public RefreshTokenFamilyAuthenticationProvider(
            AuthenticationProvider delegate,
            RefreshTokenFamilyService tokenFamilies,
            TransactionOperations transactions) {
        this.delegate = delegate;
        this.tokenFamilies = tokenFamilies;
        this.transactions = transactions;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        var refresh = (OAuth2RefreshTokenAuthenticationToken) authentication;
        Object result = transactions.execute(status -> {
            if (!tokenFamilies.lockAndValidate(refresh.getRefreshToken())) {
                return DENIED;
            }
            return delegate.authenticate(authentication);
        });
        if (result == DENIED) {
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_GRANT);
        }
        return (Authentication) result;
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return OAuth2RefreshTokenAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
