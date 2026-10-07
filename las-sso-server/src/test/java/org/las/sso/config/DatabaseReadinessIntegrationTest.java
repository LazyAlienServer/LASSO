package org.las.sso.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "server.address=127.0.0.1",
            "server.forward-headers-strategy=none",
            "las.sso.scheduling.enabled=false",
            "spring.session.jdbc.cleanup-cron=-",
            "spring.datasource.hikari.connection-timeout=2000"
        })
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class DatabaseReadinessIntegrationTest {

    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:18.4-alpine")
                    // Docker can reassign an ephemeral published port on restart; keep the fixture
                    // URL stable.
                    .withCreateContainerCmdModifier(
                            command ->
                                    command.getHostConfig()
                                            .withPortBindings(
                                                    new PortBinding(
                                                            Ports.Binding.bindIpAndPort(
                                                                    "127.0.0.1", availablePort()),
                                                            ExposedPort.tcp(5432))));

    @Autowired Environment environment;

    @Test
    @Timeout(90)
    void readinessTracksDatabaseFailureAndRecoveryWithoutFailingLiveness() throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        URI readiness =
                URI.create(
                        "http://127.0.0.1:"
                                + environment.getRequiredProperty("local.server.port")
                                + "/actuator/health/readiness");
        URI liveness =
                URI.create(
                        "http://127.0.0.1:"
                                + environment.getRequiredProperty("local.server.port")
                                + "/actuator/health/liveness");

        assertThat(request(client, readiness).statusCode()).isEqualTo(200);
        assertThat(request(client, liveness).statusCode()).isEqualTo(200);

        var docker = POSTGRES.getDockerClient();
        String containerId = POSTGRES.getContainerId();
        docker.stopContainerCmd(containerId).withTimeout(1).exec();
        try {
            HttpResponse<String> unavailable = awaitStatus(client, readiness, 503);
            assertThat(unavailable.statusCode()).isEqualTo(503);
            assertThat(unavailable.body()).isEqualTo("{\"status\":\"DOWN\"}");
            assertThat(request(client, liveness).statusCode()).isEqualTo(200);
        } finally {
            docker.startContainerCmd(containerId).exec();
        }
        assertThat(awaitStatus(client, readiness, 200).statusCode()).isEqualTo(200);
        assertThat(request(client, liveness).statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> awaitStatus(HttpClient client, URI uri, int expected)
            throws Exception {
        HttpResponse<String> last = null;
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            last = request(client, uri);
            if (last.statusCode() == expected) return last;
            Thread.sleep(250);
        }
        return last;
    }

    private HttpResponse<String> request(HttpClient client, URI uri) throws Exception {
        return client.send(
                HttpRequest.newBuilder(uri).GET().timeout(Duration.ofSeconds(5)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static int availablePort() {
        try (var socket = new ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Unable to reserve database fixture port", exception);
        }
    }
}
