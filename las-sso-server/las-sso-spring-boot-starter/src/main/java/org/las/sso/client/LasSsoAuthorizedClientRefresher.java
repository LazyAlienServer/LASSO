package org.las.sso.client;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;

@FunctionalInterface
public interface LasSsoAuthorizedClientRefresher {

    void refresh(
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response);
}
