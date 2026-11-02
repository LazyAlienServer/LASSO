package org.las.sso.api;

import org.las.sso.validation.UnicodeText;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JsonParser;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.deser.jdk.StringDeserializer;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.type.LogicalType;

@Configuration(proxyBeanMethods = false)
public class ApiJsonConfiguration {
    @Bean
    JsonMapperBuilderCustomizer strictApiScalars() {
        return builder ->
                builder.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                        .addModule(
                                new SimpleModule("valid-unicode-strings")
                                        .addDeserializer(
                                                String.class, new UnicodeStringDeserializer()))
                        .withCoercionConfig(
                                LogicalType.Textual,
                                config -> {
                                    config.setCoercion(
                                            CoercionInputShape.Integer, CoercionAction.Fail);
                                    config.setCoercion(
                                            CoercionInputShape.Float, CoercionAction.Fail);
                                    config.setCoercion(
                                            CoercionInputShape.Boolean, CoercionAction.Fail);
                                });
    }

    private static final class UnicodeStringDeserializer extends StringDeserializer {
        @Override
        public String deserialize(JsonParser parser, DeserializationContext context) {
            String value = super.deserialize(parser, context);
            if (!UnicodeText.isWellFormed(value)) {
                return context.reportInputMismatch(String.class, "Invalid Unicode string");
            }
            return value;
        }
    }
}
