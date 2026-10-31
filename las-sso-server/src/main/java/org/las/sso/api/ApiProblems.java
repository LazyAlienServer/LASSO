package org.las.sso.api;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.web.util.UriUtils;
import tools.jackson.databind.json.JsonMapper;

/** One safe error representation for application APIs, including pre-MVC failures. */
public final class ApiProblems {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ApiProblems() {}

    public static String path(HttpServletRequest request) {
        String path =
                request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI) instanceof String original
                        ? original
                        : request.getRequestURI();
        String context = request.getContextPath();
        if (!context.isEmpty() && path.startsWith(context + "/"))
            path = path.substring(context.length());
        try {
            return UriUtils.decode(path, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            return path;
        }
    }

    public static boolean isApi(HttpServletRequest request) {
        String path = path(request);
        return path.startsWith("/api/v1/") || path.startsWith("/internal/v1/");
    }

    public static UUID requestId(HttpServletRequest request) {
        if (request.getAttribute(RequestIdFilter.ATTRIBUTE) instanceof UUID id) return id;
        UUID id = UUID.randomUUID();
        request.setAttribute(RequestIdFilter.ATTRIBUTE, id);
        return id;
    }

    public static ProblemDetail create(
            int status, String code, String title, String detail, HttpServletRequest request) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), detail);
        problem.setType(
                URI.create(
                        "https://auth.las.example/problems/"
                                + code.toLowerCase(Locale.ROOT).replace('_', '-')));
        problem.setTitle(title);
        problem.setProperty("code", code);
        problem.setProperty("requestId", requestId(request));
        return problem;
    }

    public static ProblemDetail forStatus(int status, HttpServletRequest request) {
        String code =
                switch (status) {
                    case 400 -> "VALIDATION_FAILED";
                    case 401 -> "AUTHENTICATION_REQUIRED";
                    case 403 -> "ACCESS_DENIED";
                    case 404 -> "RESOURCE_NOT_FOUND";
                    case 405 -> "METHOD_NOT_ALLOWED";
                    case 406 -> "NOT_ACCEPTABLE";
                    case 413 -> "PAYLOAD_TOO_LARGE";
                    case 415 -> "UNSUPPORTED_MEDIA_TYPE";
                    case 429 -> "RATE_LIMITED";
                    case 503 -> "IDENTITY_SERVICE_UNAVAILABLE";
                    default -> "INTERNAL_SERVER_ERROR";
                };
        HttpStatus known = HttpStatus.resolve(status);
        String title = known == null ? "Request failed" : known.getReasonPhrase();
        return create(status, code, title, "The request could not be completed.", request);
    }

    public static Map<String, Object> fields(ProblemDetail problem) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("type", problem.getType().toString());
        fields.put("title", problem.getTitle());
        fields.put("status", problem.getStatus());
        fields.put("detail", problem.getDetail());
        if (problem.getProperties() != null) fields.putAll(problem.getProperties());
        return fields;
    }

    public static void write(
            HttpServletRequest request,
            HttpServletResponse response,
            int status,
            String code,
            String title)
            throws IOException {
        write(request, response, create(status, code, title, title, request));
    }

    public static void write(
            HttpServletRequest request, HttpServletResponse response, ProblemDetail problem)
            throws IOException {
        response.setStatus(problem.getStatus());
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Request-Id", requestId(request).toString());
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getOutputStream().write(JSON.writeValueAsBytes(fields(problem)));
    }
}
