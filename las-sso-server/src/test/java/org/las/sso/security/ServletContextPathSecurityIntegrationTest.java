package org.las.sso.security;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * The same real HTTP matrix under a non-root Servlet context, with no proxy/forwarded-header
 * fixture.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.servlet.context-path=/passport")
class ServletContextPathSecurityIntegrationTest extends ServletPathSecurityIntegrationTest {

    @Override
    protected String contextPath() {
        return "/passport";
    }
}
