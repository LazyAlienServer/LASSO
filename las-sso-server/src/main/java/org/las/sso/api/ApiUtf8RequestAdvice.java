package org.las.sso.api;

import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

/** JSON bytes are UTF-8; do not let JSON auto-detection silently accept UTF-16/32. */
@ControllerAdvice(basePackages = {"org.las.sso.account", "org.las.sso.admin", "org.las.sso.api"})
public final class ApiUtf8RequestAdvice extends RequestBodyAdviceAdapter {
    @Override
    public boolean supports(
            MethodParameter parameter,
            Type type,
            Class<? extends HttpMessageConverter<?>> converter) {
        return true;
    }

    @Override
    public HttpInputMessage beforeBodyRead(
            HttpInputMessage input,
            MethodParameter parameter,
            Type type,
            Class<? extends HttpMessageConverter<?>> converter)
            throws IOException {
        var contentType = input.getHeaders().getContentType();
        if (contentType == null
                || !(contentType.getSubtype().equals("json")
                        || contentType.getSubtype().endsWith("+json"))) return input;
        if (contentType.getCharset() != null
                && !StandardCharsets.UTF_8.equals(contentType.getCharset())) {
            throw new HttpMessageNotReadableException("JSON requires UTF-8", input);
        }
        var body = new PushbackInputStream(input.getBody(), 4);
        byte[] prefix = body.readNBytes(4);
        for (byte value : prefix) {
            if (value == 0 || value == (byte) 0xff || value == (byte) 0xfe) {
                throw new HttpMessageNotReadableException("JSON requires UTF-8", input);
            }
        }
        body.unread(prefix);
        return new HttpInputMessage() {
            @Override
            public InputStream getBody() {
                return body;
            }

            @Override
            public HttpHeaders getHeaders() {
                return input.getHeaders();
            }
        };
    }
}
