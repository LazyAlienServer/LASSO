package org.las.sso.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Detects API documentation drift without starting an identity database or adding public routes.
 */
class OpenApiContractTest {
    private static final Path SPEC = Path.of("../release/_source/api/openapi.json");

    @Test
    void allImplementedApplicationJsonRoutesAreDocumentedAndNoDesignRoutesAreInvented()
            throws Exception {
        JsonNode spec = JsonMapper.builder().build().readTree(Files.readString(SPEC));
        var scan = new ClassPathScanningCandidateComponentProvider(false);
        scan.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        Set<String> implemented = new HashSet<>();
        for (var bean : scan.findCandidateComponents("org.las.sso")) {
            Class<?> controller = Class.forName(bean.getBeanClassName());
            // Test-only controllers are not application API inventory.
            if (controller
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toString()
                    .contains("/test/")) continue;
            RequestMapping base =
                    AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
            String[] prefixes =
                    base == null || base.path().length == 0 ? new String[] {""} : base.path();
            for (var method : controller.getDeclaredMethods()) {
                RequestMapping mapping =
                        AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) continue;
                String[] suffixes = mapping.path().length == 0 ? new String[] {""} : mapping.path();
                for (String prefix : prefixes) {
                    for (String suffix : suffixes) {
                        for (var verb : mapping.method()) {
                            String route = prefix + suffix;
                            if (route.startsWith("/api/v1/") || route.startsWith("/internal/v1/"))
                                implemented.add(
                                        verb.name().toLowerCase(java.util.Locale.ROOT)
                                                + " "
                                                + route);
                        }
                    }
                }
            }
        }
        Set<String> documented = new HashSet<>();
        spec.path("paths")
                .properties()
                .forEach(
                        entry -> {
                            if (entry.getKey().startsWith("/api/v1/")
                                    || entry.getKey().startsWith("/internal/v1/")) {
                                entry.getValue()
                                        .properties()
                                        .forEach(
                                                operation -> {
                                                    if (Set.of(
                                                                    "get", "post", "put", "patch",
                                                                    "delete")
                                                            .contains(operation.getKey()))
                                                        documented.add(
                                                                operation.getKey()
                                                                        + " "
                                                                        + entry.getKey());
                                                });
                            }
                        });
        assertThat(implemented).isNotEmpty();
        assertThat(documented).containsExactlyInAnyOrderElementsOf(implemented);
    }

    @Test
    void documentedJavaRecordFieldsMatchTheActualWireDtos() throws Exception {
        JsonNode schemas =
                JsonMapper.builder()
                        .build()
                        .readTree(Files.readString(SPEC))
                        .path("components")
                        .path("schemas");
        int checked = 0;
        for (var entry : schemas.properties()) {
            String type = entry.getValue().path("x-java-type").asText("");
            if (type.isEmpty()) continue;
            Class<?> record = Class.forName(type);
            assertThat(record.isRecord()).as(type).isTrue();
            Set<String> fields = new HashSet<>();
            entry.getValue()
                    .path("properties")
                    .properties()
                    .forEach(property -> fields.add(property.getKey()));
            assertThat(fields)
                    .as(entry.getKey())
                    .containsExactlyInAnyOrderElementsOf(
                            Arrays.stream(record.getRecordComponents())
                                    .map(java.lang.reflect.RecordComponent::getName)
                                    .toList());
            checked++;
        }
        assertThat(checked).isGreaterThanOrEqualTo(35);
    }

    @Test
    void recoveryEnumsAndExceptionalPasswordErrorAreNotMisrepresented() throws Exception {
        JsonNode spec = JsonMapper.builder().build().readTree(Files.readString(SPEC));
        var values =
                spec.path("components")
                        .path("schemas")
                        .path("StartRecoveryRequest")
                        .path("properties")
                        .path("credentialType")
                        .path("enum");
        Set<String> documented = new HashSet<>();
        values.forEach(value -> documented.add(value.asText()));
        assertThat(documented)
                .containsExactlyInAnyOrderElementsOf(
                        Arrays.stream(
                                        org.las.sso.account.PasswordRecoveryService.CredentialType
                                                .values())
                                .map(Enum::name)
                                .toList());
        assertThat(
                        spec.path("paths")
                                .path("/api/v1/me/password-change")
                                .path("post")
                                .path("responses")
                                .path("400")
                                .path("content")
                                .has("application/json"))
                .isTrue();
        assertThat(spec.path("paths").path("/api/v1/recovery/password").has("post")).isFalse();
    }
}
