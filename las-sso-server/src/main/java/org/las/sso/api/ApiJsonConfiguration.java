package org.las.sso.api;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

@Configuration(proxyBeanMethods = false)
public class ApiJsonConfiguration {
    @Bean
    JsonMapperBuilderCustomizer strictApiScalars() {
        return builder ->
                builder.withCoercionConfig(
                        LogicalType.Textual,
                        config -> {
                            config.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail);
                            config.setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
                            config.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
                        });
    }
}
