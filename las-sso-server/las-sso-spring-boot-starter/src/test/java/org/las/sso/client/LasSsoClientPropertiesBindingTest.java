package org.las.sso.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class LasSsoClientPropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void bindsRecordWithConvenienceConstructor() {
        contextRunner
                .withPropertyValues(
                        "las.sso.client.registration-id=custom-sso",
                        "las.sso.client.refresh-before-expiry=PT30S",
                        "las.sso.client.logout-success-uri=/signed-out")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var properties = context.getBean(LasSsoClientProperties.class);
                    assertThat(properties.registrationId()).isEqualTo("custom-sso");
                    assertThat(properties.refreshBeforeExpiry()).isEqualTo(Duration.ofSeconds(30));
                    assertThat(properties.logoutSuccessUri()).isEqualTo("/signed-out");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(LasSsoClientProperties.class)
    static class PropertiesConfiguration {
    }
}
