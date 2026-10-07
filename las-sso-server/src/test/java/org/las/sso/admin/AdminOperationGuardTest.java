package org.las.sso.admin;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.las.sso.identity.LasUserPrincipal;
import org.las.sso.security.RecentAuthenticationService;
import org.las.sso.security.SameOriginVerifier;
import org.las.sso.session.UserSessionService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class AdminOperationGuardTest {

    @Test
    void staleAdminAuthorityIsRejectedWhenDatabaseRoleOrAdminSessionIsMissing() {
        JdbcClient jdbcClient = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
        when(jdbcClient
                        .sql(anyString())
                        .param(anyString(), org.mockito.ArgumentMatchers.any())
                        .param(anyString(), org.mockito.ArgumentMatchers.any())
                        .query(Boolean.class)
                        .single())
                .thenReturn(false);
        SameOriginVerifier origin = mock(SameOriginVerifier.class);
        RecentAuthenticationService recent = mock(RecentAuthenticationService.class);
        UserSessionService sessions = mock(UserSessionService.class);
        when(sessions.currentSid(org.mockito.ArgumentMatchers.any())).thenReturn("stale-sid");
        when(recent.isRecent(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        var principal =
                new LasUserPrincipal(
                        UUID.randomUUID(),
                        "OldAdmin",
                        "{noop}irrelevant",
                        1,
                        true,
                        false,
                        List.of(new SimpleGrantedAuthority("ROLE_SSO_ADMIN")));
        var authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        principal, null, principal.getAuthorities());

        var guard = new AdminOperationGuard(jdbcClient, origin, recent, sessions);

        assertThatThrownBy(
                        () ->
                                guard.authorizeWrite(
                                        authentication,
                                        new MockHttpServletRequest(
                                                "POST", "/api/v1/admin/clients")))
                .isInstanceOf(AdminUserManagementException.class)
                .extracting("code")
                .isEqualTo("ADMIN_ACCESS_DENIED");
    }
}
