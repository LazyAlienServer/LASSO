package org.las.sso.account;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.las.sso.config.RecoveryProperties;

class RecoveryCodeServiceTest {

    @Test
    void generatesTenUniqueDisplayCodesAndOnlyStableHmacDigests() {
        var service = new RecoveryCodeService(new RecoveryProperties("test-pepper"));

        var batch = service.generateBatch();

        assertThat(batch).hasSize(10);
        assertThat(batch).extracting(RecoveryCodeService.RecoveryCode::plaintext)
                .doesNotHaveDuplicates()
                .allMatch(code -> code.matches("LAS-[2-9A-HJ-NP-Z]{4}(-[2-9A-HJ-NP-Z]{4}){2}"));
        var first = batch.getFirst();
        assertThat(first.digest()).hasSize(32).isEqualTo(service.digest(first.plaintext()));
        assertThat(new String(first.digest(), java.nio.charset.StandardCharsets.UTF_8))
                .isNotEqualTo(first.plaintext());
    }
}
