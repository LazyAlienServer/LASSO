package org.las.sso.session;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class JdbcSessionPersistenceTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:18.4-alpine");

    @Autowired
    ApplicationContext applicationContext;

    @Autowired
    SessionRepository<?> sessionRepository;

    @Autowired
    JdbcClient jdbcClient;

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void servletSessionsAreStoredInJdbc() {
        assertThat(applicationContext.containsBean("springSessionRepositoryFilter")).isTrue();
        assertThat(sessionRepository).isInstanceOf(JdbcIndexedSessionRepository.class);

        SessionRepository repository = sessionRepository;
        Session session = (Session) repository.createSession();
        session.setAttribute("restart-proof", "present");
        repository.save(session);

        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM spring_session WHERE session_id = :id")
                .param("id", session.getId())
                .query(Long.class)
                .single()).isOne();
        assertThat((Session) repository.findById(session.getId()))
                .isNotNull()
                .extracting(stored -> stored.getAttribute("restart-proof"))
                .isEqualTo("present");
    }
}
