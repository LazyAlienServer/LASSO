package org.las.sso.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.las.sso.identity.LasUserPrincipal;
import org.las.sso.security.RecentAuthenticationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ProfileServiceTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:18.4-alpine");

    @Autowired
    AccountRegistrationService registrationService;

    @Autowired
    ProfileService profiles;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    MockMvc mockMvc;

    @Test
    void updateDisplayNameBumpsProfileVersionAndWritesEvent() {
        var account = registrationService.register(
                "ProfileNameUser", "Old Name", "profile name passphrase");
        long before = profileVersion(account.userId());

        ProfileService.View view = profiles.updateProfile(
                account.userId(), "New Name", "default-alien-01", UUID.randomUUID());

        assertThat(view.displayName()).isEqualTo("New Name");
        assertThat(view.avatarUrl()).isEqualTo("/assets/avatars/default-alien-01.svg");
        assertThat(profileVersion(account.userId())).isEqualTo(before + 1);
        assertThat(jdbcClient.sql("""
                SELECT COUNT(*) FROM security_events
                 WHERE user_id = :userId AND event_type = 'DISPLAY_NAME_UPDATED'
                """).param("userId", account.userId()).query(Long.class).single()).isOne();
    }

    @Test
    void updateDisplayNameRejectsBlankAndOverlong() {
        var account = registrationService.register(
                "ProfileLongUser", "Long Name", "profile long passphrase");
        assertThatThrownBy(() -> profiles.updateProfile(
                account.userId(), "   ", null, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
        String overlong = "长".repeat(65);
        assertThatThrownBy(() -> profiles.updateProfile(
                account.userId(), overlong, null, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void changeUsernameClosesOldRowInsertsNewAndBumpsVersion() {
        var account = registrationService.register(
                "ChangeNameUser", "Change Name", "change name passphrase");
        backdateUsernameChange(account.userId());
        long before = profileVersion(account.userId());

        ProfileService.View view = profiles.changeUsername(
                account.userId(), "BrandNewName", "change name passphrase", UUID.randomUUID());

        assertThat(view.username()).isEqualTo("BrandNewName");
        assertThat(profileVersion(account.userId())).isEqualTo(before + 1);
        Long activeRows = jdbcClient.sql("""
                SELECT COUNT(*) FROM usernames WHERE user_id = :userId AND valid_to IS NULL
                """).param("userId", account.userId()).query(Long.class).single();
        assertThat(activeRows).isOne();
        Long historyRows = jdbcClient.sql("""
                SELECT COUNT(*) FROM usernames WHERE user_id = :userId AND valid_to IS NOT NULL
                """).param("userId", account.userId()).query(Long.class).single();
        assertThat(historyRows).isOne();
        assertThat(jdbcClient.sql("""
                SELECT COUNT(*) FROM security_events
                 WHERE user_id = :userId AND event_type = 'USERNAME_CHANGED'
                """).param("userId", account.userId()).query(Long.class).single()).isOne();
    }

    @Test
    void changeUsernameRejectsWrongPasswordAndCooldown() {
        var account = registrationService.register(
                "CooldownUser", "Cooldown Name", "cooldown passphrase");
        assertThatThrownBy(() -> profiles.changeUsername(
                account.userId(), "NewNameOne", "wrong password", UUID.randomUUID()))
                .isInstanceOf(RecentAuthenticationException.class);

        profiles.changeUsername(account.userId(), "NewNameOne",
                "cooldown passphrase", UUID.randomUUID());
        OffsetDateTime availableAt = jdbcClient.sql("""
                SELECT MAX(valid_from) + INTERVAL '90 days' FROM usernames
                 WHERE user_id = :userId
                   AND change_reason IN ('SELF_SERVICE', 'USERNAME_CHANGE',
                                         'USERNAME_REUSE_HISTORICAL')
                """).param("userId", account.userId())
                .query(OffsetDateTime.class).single();
        assertThatThrownBy(() -> profiles.changeUsername(
                account.userId(), "NewNameTwo", "cooldown passphrase", UUID.randomUUID()))
                .isInstanceOfSatisfying(UsernameCooldownException.class, exception ->
                        assertThat(exception.availableAt()).isEqualTo(availableAt));
    }

    @Test
    void caseOnlyChangeCountsTowardsCooldown() {
        var account = registrationService.register(
                "CaseChangeUser", "Case Change", "case change passphrase");
        backdateUsernameChange(account.userId());

        profiles.changeUsername(account.userId(), "CASECHANGEUSER",
                "case change passphrase", UUID.randomUUID());
        assertThatThrownBy(() -> profiles.changeUsername(
                account.userId(), "Casechangeuser", "case change passphrase",
                UUID.randomUUID()))
                .isInstanceOf(UsernameCooldownException.class);
    }

    @Test
    void historicalNamesCannotBeReusedEvenByTheirPreviousOwner() {
        var account = registrationService.register(
                "ReuseOwnerUser", "Reuse Owner", "reuse owner passphrase");
        var other = registrationService.register(
                "ReuseOtherUser", "Reuse Other", "reuse other passphrase");
        backdateUsernameChange(account.userId());
        backdateUsernameChange(other.userId());

        profiles.changeUsername(account.userId(), "TemporaryName",
                "reuse owner passphrase", UUID.randomUUID());
        backdateUsernameChange(account.userId());
        assertThatThrownBy(() -> profiles.changeUsername(
                account.userId(), "ReuseOwnerUser", "reuse owner passphrase",
                UUID.randomUUID()))
                .isInstanceOf(UsernameUnavailableException.class);

        assertThatThrownBy(() -> profiles.changeUsername(
                other.userId(), "TemporaryName", "reuse other passphrase",
                UUID.randomUUID()))
                .isInstanceOf(UsernameUnavailableException.class);
    }

    @Test
    void endpointsRequireLoginAndReturnProfile() throws Exception {
        var account = registrationService.register(
                "ProfileApiUser", "Profile Api", "profile api passphrase");
        backdateUsernameChange(account.userId());
        LasUserPrincipal principal = new LasUserPrincipal(
                account.userId(), "ProfileApiUser", "{noop}irrelevant", 1, false, false,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));

        mockMvc.perform(get("/api/v1/me/profile").with(user(principal)))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/me/profile").with(user(principal)).with(csrf())
                        .header("Origin", "http://127.0.0.1:9000")
                        .contentType("application/json")
                        .content("{\"displayName\":\"Api New Name\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/me/username-change").with(user(principal)).with(csrf())
                        .header("Origin", "http://127.0.0.1:9000")
                        .contentType("application/json")
                        .content("{\"newUsername\":\"ApiNewName\",\"currentPassword\":\"profile api passphrase\"}"))
                .andExpect(status().isOk());
    }

    private long profileVersion(UUID userId) {
        return jdbcClient.sql("SELECT profile_version FROM users WHERE id = :userId")
                .param("userId", userId).query(Long.class).single();
    }

    private void backdateUsernameChange(UUID userId) {
        jdbcClient.sql("""
                UPDATE usernames SET valid_from = :past
                 WHERE user_id = :userId AND valid_to IS NULL
                   AND change_reason IN ('SELF_SERVICE', 'USERNAME_CHANGE',
                                         'USERNAME_REUSE_HISTORICAL')
                """).param("past", OffsetDateTime.now(ZoneOffset.UTC).minusDays(91))
                .param("userId", userId).update();
    }
}
