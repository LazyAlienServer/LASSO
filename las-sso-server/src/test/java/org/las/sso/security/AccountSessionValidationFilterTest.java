package org.las.sso.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.las.sso.identity.LasUserPrincipal;
import org.las.sso.session.UserSessionService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class AccountSessionValidationFilterTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void revokedDatabaseSessionInvalidatesSpringSessionAndStopsRequest() throws Exception {
        JdbcClient jdbcClient = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
        when(jdbcClient.sql(anyString()).param(anyString(), org.mockito.ArgumentMatchers.any())
                .param(anyString(), org.mockito.ArgumentMatchers.any())
                .query(Boolean.class).single()).thenReturn(true);
        UserSessionService sessions = mock(UserSessionService.class);
        when(sessions.validateOrRegister(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(false);
        var principal = new LasUserPrincipal(
                UUID.randomUUID(), "SessionUser", "{noop}irrelevant", 1,
                true, false, List.of());
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
        var request = new MockHttpServletRequest("GET", "/api/v1/me/profile");
        request.getSession(true);
        var response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        new AccountSessionValidationFilter(jdbcClient, sessions)
                .doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("SESSION_EXPIRED");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(chain);
    }
}
