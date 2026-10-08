package org.las.sso.session;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.las.sso.identity.LasUserPrincipal;
import org.las.sso.oidc.OidcReauthenticationService;
import org.las.sso.security.RecentAuthenticationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
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

    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:18.4-alpine");

    @Autowired ApplicationContext applicationContext;

    @Autowired SessionRepository<?> sessionRepository;

    @Autowired JdbcClient jdbcClient;

    @Autowired OidcReauthenticationService challenges;

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void servletSessionsAreStoredInJdbc() {
        assertThat(applicationContext.containsBean("springSessionRepositoryFilter")).isTrue();
        assertThat(sessionRepository).isInstanceOf(JdbcIndexedSessionRepository.class);

        SessionRepository repository = sessionRepository;
        Session session = (Session) repository.createSession();
        session.setAttribute("restart-proof", "present");
        repository.save(session);

        assertThat(
                        jdbcClient
                                .sql("SELECT COUNT(*) FROM spring_session WHERE session_id = :id")
                                .param("id", session.getId())
                                .query(Long.class)
                                .single())
                .isOne();
        assertThat((Session) repository.findById(session.getId()))
                .isNotNull()
                .extracting(stored -> stored.getAttribute("restart-proof"))
                .isEqualTo("present");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void existingJdbcExpiryCleanupRemovesAttributesButRetainsLiveSession() {
        SessionRepository repository = sessionRepository;
        Session expired = (Session) repository.createSession();
        expired.setAttribute("fixture", "expired");
        repository.save(expired);
        Session live = (Session) repository.createSession();
        live.setAttribute("fixture", "live");
        repository.save(live);
        jdbcClient
                .sql("UPDATE spring_session SET expiry_time=1 WHERE session_id=:id")
                .param("id", expired.getId())
                .update();
        ((JdbcIndexedSessionRepository) sessionRepository).cleanUpExpiredSessions();
        assertThat(repository.findById(expired.getId())).isNull();
        assertThat(
                        jdbcClient
                                .sql(
                                        "SELECT count(*) FROM spring_session_attributes WHERE session_primary_id NOT IN (SELECT primary_id FROM spring_session)")
                                .query(Long.class)
                                .single())
                .isZero();
        assertThat(repository.findById(live.getId())).isNotNull();
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void passwordChallengeSurvivesJdbcReloadAndOnlyOneCompetingCopyCanConsumeIt() throws Exception {
        var principal =
                new LasUserPrincipal(
                        java.util.UUID.randomUUID(),
                        "ChallengeUser",
                        "irrelevant",
                        true,
                        java.util.List.of());
        var request = new MockHttpServletRequest();
        var browser = new MockHttpSession();
        request.setSession(browser);
        request.addParameter("state", "original-state");
        request.addParameter("nonce", "original-nonce");
        browser.setAttribute(UserSessionService.SID_SESSION_ATTRIBUTE, "stable-test-sid");
        browser.setAttribute(
                RecentAuthenticationService.SESSION_ATTRIBUTE, java.time.Instant.now());
        String id = challenges.begin(request, principal);
        String pendingAttribute =
                java.util.Collections.list(browser.getAttributeNames()).stream()
                        .filter(name -> name.endsWith(".PENDING"))
                        .findFirst()
                        .orElseThrow();
        SessionRepository repository = sessionRepository;
        Session stored = (Session) repository.createSession();
        stored.setAttribute("challenge", browser.getAttribute(pendingAttribute));
        repository.save(stored);
        Session restored = (Session) repository.findById(stored.getId());
        browser.setAttribute(pendingAttribute, restored.getAttribute("challenge"));
        challenges.complete(request, principal, id);

        stored.setAttribute("challenge", browser.getAttribute(pendingAttribute));
        repository.save(stored);
        restored = (Session) repository.findById(stored.getId());
        Object completed = restored.getAttribute("challenge");
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var results = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int attempt = 0; attempt < 2; attempt++) {
                results.add(
                        executor.submit(
                                () -> {
                                    var copy = new MockHttpServletRequest();
                                    var session = new MockHttpSession();
                                    session.setAttribute(pendingAttribute, completed);
                                    session.setAttribute(
                                            UserSessionService.SID_SESSION_ATTRIBUTE,
                                            "stable-test-sid");
                                    session.setAttribute(
                                            RecentAuthenticationService.SESSION_ATTRIBUTE,
                                            java.time.Instant.now());
                                    copy.setSession(session);
                                    copy.addParameter("state", "original-state");
                                    copy.addParameter("nonce", "original-nonce");
                                    ready.countDown();
                                    start.await();
                                    return challenges.consumeCompletion(copy, principal);
                                }));
            }
            assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(
                            results.get(0).get(10, java.util.concurrent.TimeUnit.SECONDS)
                                    ^ results.get(1).get(10, java.util.concurrent.TimeUnit.SECONDS))
                    .isTrue();
        }
    }
}
