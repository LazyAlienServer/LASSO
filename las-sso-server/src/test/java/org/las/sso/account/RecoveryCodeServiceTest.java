package org.las.sso.account;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.las.sso.config.RecoveryProperties;

class RecoveryCodeServiceTest {

    @Test
    void ordinaryCodesContainAtLeastEightyBitsOfRandomSymbolsExcludingPrefixAndSeparators() {
        var service = new RecoveryCodeService(new RecoveryProperties("test-pepper"));
        for (var code : service.generateBatch()) {
            String symbols = code.plaintext().substring("LAS-".length()).replace("-", "");
            assertThat(symbols).matches("[2-9A-HJ-NP-Z]+");
            assertThat(symbols.length() * 5).as("32-symbol alphabet contributes five bits per random symbol")
                    .isGreaterThanOrEqualTo(80);
        }
    }

    @Test
    void administratorCredentialContainsAtLeastEightyBitsOfRandomSymbolsExcludingPrefixAndSeparators() {
        var service = new RecoveryCodeService(new RecoveryProperties("test-pepper"));
        String symbols = service.generateAdminCredential().plaintext().substring("LAS-ADMIN-".length()).replace("-", "");
        assertThat(symbols).matches("[2-9A-HJ-NP-Z]+");
        assertThat(symbols.length() * 5).as("fixed LAS-ADMIN prefix contributes no entropy")
                .isGreaterThanOrEqualTo(80);
    }

    @Test
    void generatesTenUniqueDisplayCodesAndOnlyStableHmacDigests() {
        var service = new RecoveryCodeService(new RecoveryProperties("test-pepper"));

        var batch = service.generateBatch();

        assertThat(batch).hasSize(10);
        assertThat(batch).extracting(RecoveryCodeService.RecoveryCode::plaintext)
                .doesNotHaveDuplicates()
                .allMatch(code -> code.matches("LAS-[2-9A-HJ-NP-Z]{4}(-[2-9A-HJ-NP-Z]{4}){3}"));
        var first = batch.getFirst();
        assertThat(first.digest()).hasSize(32).isEqualTo(service.digest(first.plaintext()));
        assertThat(new String(first.digest(), java.nio.charset.StandardCharsets.UTF_8))
                .isNotEqualTo(first.plaintext());
    }

    @Test
    void newAdministratorCredentialUsesFourGroupsAndStableHmac() {
        var service = new RecoveryCodeService(new RecoveryProperties("test-pepper"));
        var credential = service.generateAdminCredential();

        assertThat(credential.plaintext()).matches("LAS-ADMIN-[2-9A-HJ-NP-Z]{4}(-[2-9A-HJ-NP-Z]{4}){3}");
        assertThat(credential.digest()).hasSize(32).isEqualTo(service.digest(credential.plaintext()));
    }

    @ParameterizedTest
    @CsvSource({
            "LAS-2345-6789-ABCD, e36b42bf862906369000bc7c677a1757a01ddcf56fbcab1c52f9d0d02dfa1418",
            "LAS-ADMIN-EFGH-JKLM-NPQR, be21d8b49f7039b6651162f9cbf3a2fd94d39d1a4fd69fd6d5f61a0b72bd7da0",
            "LAS-2345-6789-ABCD-EFGH, 85dc27efa9422cd6db31589aba61e2a9aa97390b42d00c655ad4a0dd6392a96b"
    })
    void legacyAndNewDigestVectorsKeepCaseAndSeparatorNormalization(String code, String expectedHex) {
        var service = new RecoveryCodeService(new RecoveryProperties("test-pepper"));
        byte[] expected = java.util.HexFormat.of().parseHex(expectedHex);

        assertThat(service.digest(code)).isEqualTo(expected);
        assertThat(service.digest(code.toLowerCase(java.util.Locale.ROOT).replace("-", ""))).isEqualTo(expected);
    }

    @Test
    void ordinaryAndAdministratorPrefixesRemainDistinctDigestDomains() {
        var service = new RecoveryCodeService(new RecoveryProperties("test-pepper"));

        assertThat(service.digest("LAS-2345-6789-ABCD-EFGH"))
                .isNotEqualTo(service.digest("LAS-ADMIN-2345-6789-ABCD-EFGH"));
    }

    @Test
    void storedDigestRemainsProtectedFromCallerArrayMutation() {
        byte[] input = new byte[32];
        var code = new RecoveryCodeService.RecoveryCode("fixture", input);
        input[0] = 1;
        byte[] returned = code.digest();
        returned[1] = 2;

        assertThat(code.digest()).containsOnly((byte) 0);
    }
}
