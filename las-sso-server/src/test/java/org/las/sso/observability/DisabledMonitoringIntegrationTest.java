package org.las.sso.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1", "las.sso.scheduling.enabled=false", "spring.session.jdbc.cleanup-cron=-",
        "management.endpoints.web.exposure.include=health,prometheus", "las.sso.monitoring.enabled=false"
})
@ActiveProfiles("local")
@Testcontainers(disabledWithoutDocker = true)
class DisabledMonitoringIntegrationTest {

    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.4-alpine");
    @Autowired Environment environment;

    @Test
    void manualActuatorExposureWithoutMonitoringStillDeniesAnonymousAndOidcClient() throws Exception {
        var uri = URI.create("http://127.0.0.1:" + environment.getRequiredProperty("local.server.port") + "/actuator/prometheus");
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        assertThat(http.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        String credential = "Basic " + Base64.getEncoder().encodeToString("laslog-bff:laslog-local-only".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(http.send(HttpRequest.newBuilder(uri).header("Authorization", credential).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
    }
}
