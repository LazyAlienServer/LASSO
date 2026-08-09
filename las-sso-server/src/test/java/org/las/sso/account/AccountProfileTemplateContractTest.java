package org.las.sso.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class AccountProfileTemplateContractTest {

    @Test
    void profileEditorHidesAvatarSelectionAndPreservesTheCurrentAvatar() throws IOException {
        try (var stream = getClass().getResourceAsStream("/templates/account.html")) {
            assertThat(stream).isNotNull();
            String template = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(template)
                    .contains("name=\"avatarResourceId\" type=\"hidden\"")
                    .doesNotContain("class=\"avatar-picker\"")
                    .doesNotContain("id=\"profile-avatar-image\"")
                    .doesNotContain("id=\"avatar-help\"");
        }
    }

    @Test
    void usernameEditorGroupsFieldsForStableVerticalSpacing() throws IOException {
        try (var templateStream = getClass().getResourceAsStream("/templates/account.html");
                var stylesheetStream = getClass().getResourceAsStream("/static/assets/passport.css")) {
            assertThat(templateStream).isNotNull();
            assertThat(stylesheetStream).isNotNull();
            String template = new String(templateStream.readAllBytes(), StandardCharsets.UTF_8);
            String stylesheet = new String(stylesheetStream.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(template)
                    .contains("class=\"passport-dialog-card username-dialog-card\"")
                    .contains("class=\"username-dialog-fields\"");
            assertThat(stylesheet)
                    .contains(".username-dialog-fields { display: grid; gap: 24px; }")
                    .contains(".username-dialog-card .dialog-actions { margin-top: 16px; }");
        }
    }
}
