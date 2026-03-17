package ru.itmo.hhprocess.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Value("${app.openapi.title}")
    private String openApiTitle;

    @Value("${app.openapi.version}")
    private String openApiVersion;

    @Value("${app.openapi.description}")
    private String openApiDescription;

    @Bean
    public ModelResolver modelResolver(ObjectMapper objectMapper) {
        return new ModelResolver(objectMapper);
    }

    @Bean
    public OpenAPI api() {
        final String scheme = "bearerAuth";
        return new OpenAPI()
                .info(new Info()
                        .title(openApiTitle)
                        .version(openApiVersion)
                        .description(openApiDescription))
                .addSecurityItem(new SecurityRequirement().addList(scheme))
                .schemaRequirement(scheme, new SecurityScheme()
                        .name(scheme)
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT"));
    }
}
