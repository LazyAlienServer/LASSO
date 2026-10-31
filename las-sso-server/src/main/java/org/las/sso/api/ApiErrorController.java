package org.las.sso.api;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.web.WebProperties;
import org.springframework.boot.webmvc.autoconfigure.error.BasicErrorController;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorViewResolver;
import org.springframework.boot.webmvc.error.ErrorAttributes;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.ModelAndView;

/** Keep browser/protocol error handling while covering application Servlet error dispatch. */
@Controller
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("${server.error.path:${error.path:/error}}")
public final class ApiErrorController extends BasicErrorController {
    public ApiErrorController(
            ErrorAttributes attributes, WebProperties properties, List<ErrorViewResolver> views) {
        super(attributes, properties.getError(), views);
    }

    @Override
    @RequestMapping
    public ResponseEntity<Map<String, Object>> error(HttpServletRequest request) {
        if (!ApiProblems.isApi(request)) return super.error(request);
        return ResponseEntity.status(errorStatus(request))
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .header("X-Request-Id", ApiProblems.requestId(request).toString())
                .cacheControl(CacheControl.noStore())
                .body(ApiProblems.fields(ApiProblems.forStatus(errorStatus(request), request)));
    }

    @Override
    @RequestMapping(produces = MediaType.TEXT_HTML_VALUE)
    public ModelAndView errorHtml(HttpServletRequest request, HttpServletResponse response) {
        if (!ApiProblems.isApi(request)) return super.errorHtml(request, response);
        try {
            ApiProblems.write(
                    request, response, ApiProblems.forStatus(errorStatus(request), request));
        } catch (IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
        return null;
    }

    private static int errorStatus(HttpServletRequest request) {
        return request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) instanceof Integer status
                ? status
                : 500;
    }
}
