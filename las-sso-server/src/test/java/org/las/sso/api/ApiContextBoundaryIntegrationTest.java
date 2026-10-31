package org.las.sso.api;

import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "server.address=127.0.0.1",
            "server.servlet.context-path=/passport",
            "las.sso.scheduling.enabled=false",
            "spring.session.jdbc.cleanup-cron=-"
        })
class ApiContextBoundaryIntegrationTest extends ApiBoundaryIntegrationTest {
    @Override
    protected String contextPath() {
        return "/passport";
    }
}
