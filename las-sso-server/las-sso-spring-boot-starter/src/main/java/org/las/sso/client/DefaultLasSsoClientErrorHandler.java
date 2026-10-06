package org.las.sso.client;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Default problem response, retaining the existing refresh error and browser-expiry behavior. */
public final class DefaultLasSsoClientErrorHandler implements LasSsoClientErrorHandler {

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, Failure failure) throws IOException {
        if (failure == Failure.SESSION_EXPIRED) {
            String path = request.getRequestURI().substring(request.getContextPath().length());
            if (!path.startsWith("/api/")) {
                response.sendRedirect(request.getContextPath() + "/api/auth/login");
                return;
            }
        }
        int status = failure == Failure.SESSION_EXPIRED ? 401 : 503;
        String slug = switch (failure) {
            case SESSION_EXPIRED -> "session-expired";
            case SSO_UNAVAILABLE -> "sso-unavailable";
            case SESSION_REGISTRATION_FAILED -> "session-registration-failed";
        };
        String title = switch (failure) {
            case SESSION_EXPIRED -> "登录会话已失效";
            case SSO_UNAVAILABLE -> "身份中心暂时不可用";
            case SESSION_REGISTRATION_FAILED -> "登录会话登记失败";
        };
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"type\":\"https://auth.las.example/problems/" + slug
                + "\",\"title\":\"" + title + "\",\"status\":" + status + ",\"code\":\"" + failure.name() + "\"}");
    }
}
