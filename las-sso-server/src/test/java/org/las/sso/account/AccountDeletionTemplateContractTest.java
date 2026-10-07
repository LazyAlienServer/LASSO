package org.las.sso.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class AccountDeletionTemplateContractTest {

    @Test
    void deletionPasswordAndActionUseOneConsistentSpacingLayer() throws IOException {
        try (var templateStream = getClass().getResourceAsStream("/templates/account-delete.html");
                var stylesheetStream =
                        getClass().getResourceAsStream("/static/assets/passport.css")) {
            assertThat(templateStream).isNotNull();
            assertThat(stylesheetStream).isNotNull();
            String template = new String(templateStream.readAllBytes(), StandardCharsets.UTF_8);
            String stylesheet = new String(stylesheetStream.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(template).contains("class=\"deletion-password-field\"");
            assertThat(stylesheet)
                    .contains(
                            ".deletion-request-form { margin: 12px auto 0; width: min(320px, 100%); display: grid; gap: 16px; }")
                    .contains(".deletion-password-field .field-group { margin: 0; }");
        }
    }
}
