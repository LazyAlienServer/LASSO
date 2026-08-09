package org.las.sso.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class AdminUsersTemplateContractTest {

    @Test
    void userPaginationExposesPreviousAndNextControls() throws IOException {
        try (var stream = getClass().getResourceAsStream("/templates/admin-users.html")) {
            assertThat(stream).isNotNull();
            String template = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(template)
                    .contains("← 上一页")
                    .contains("下一页 →")
                    .contains("${currentCursor != null}")
                    .contains("cursor=${previousCursor}")
                    .contains("cursor=${currentCursor},selected=${item.userId}");
        }
    }

    @Test
    void userPaginationStaysAtTheBottomOfTheListPanel() throws IOException {
        try (var stream = getClass().getResourceAsStream("/static/assets/passport.css")) {
            assertThat(stream).isNotNull();
            String stylesheet = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(stylesheet)
                    .contains(".admin-list-panel { display: flex; flex-direction: column; }")
                    .contains(".admin-pagination.user-pagination { flex: 0 0 auto; margin-top: auto;");
        }
    }
}
