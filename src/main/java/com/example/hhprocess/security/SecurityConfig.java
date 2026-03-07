package com.example.hhprocess.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthenticationFilter jwtAuthenticationFilter,
                                                   AuthenticationProvider authenticationProvider,
                                                   RestAuthenticationEntryPoint authenticationEntryPoint) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exception -> exception.authenticationEntryPoint(authenticationEntryPoint))
                .authenticationProvider(authenticationProvider)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/v1/auth/**",
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/v3/api-docs/**"
                        ).permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/vacancies/**").hasRole("HR")
                        .requestMatchers(HttpMethod.POST, "/api/v1/applications/*/validate").hasRole("HR")
                        .requestMatchers(HttpMethod.POST, "/api/v1/applications/*/auto-reject").hasRole("HR")
                        .requestMatchers(HttpMethod.POST, "/api/v1/applications/*/reject").hasRole("HR")
                        .requestMatchers(HttpMethod.POST, "/api/v1/applications/*/invite").hasRole("HR")
                        .requestMatchers(HttpMethod.POST, "/api/v1/applications/*/reserve").hasRole("HR")
                        .requestMatchers(HttpMethod.POST, "/api/v1/applications/*/expire").hasRole("HR")
                        .requestMatchers(HttpMethod.GET, "/api/v1/applications/*/history").hasRole("HR")
                        .requestMatchers(HttpMethod.GET, "/api/v1/applications/**").hasRole("HR")
                        .requestMatchers(HttpMethod.GET, "/api/v1/candidates/**").hasRole("HR")
                        .requestMatchers(HttpMethod.GET, "/api/v1/vacancies/**").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/candidates/**").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/applications").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/applications/*/accept").authenticated()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public AuthenticationProvider authenticationProvider(AppUserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return provider;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }
}
