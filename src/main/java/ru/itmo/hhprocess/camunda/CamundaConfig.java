package ru.itmo.hhprocess.camunda;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(CamundaProperties.class)
public class CamundaConfig {

    @Bean
    public RestTemplate camundaRestTemplate(RestTemplateBuilder builder, CamundaProperties properties) {
        RestTemplate restTemplate = builder
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(30))
                .build();

        if (hasText(properties.getUsername())) {
            List<ClientHttpRequestInterceptor> interceptors = new ArrayList<>(restTemplate.getInterceptors());
            interceptors.add((request, body, execution) -> {
                String token = properties.getUsername() + ":" + properties.getPassword();
                String encoded = Base64.getEncoder().encodeToString(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                request.getHeaders().set("Authorization", "Basic " + encoded);
                return execution.execute(request, body);
            });
            restTemplate.setInterceptors(interceptors);
        }
        return restTemplate;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
