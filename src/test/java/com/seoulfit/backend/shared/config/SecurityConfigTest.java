package com.seoulfit.backend.shared.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.seoulfit.backend.user.application.service.CustomOAuth2UserService;
import com.seoulfit.backend.user.infrastructure.security.JwtAuthenticationFilter;
import com.seoulfit.backend.user.infrastructure.security.OAuth2LoginSuccessHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

@DisplayName("보안 설정")
class SecurityConfigTest {

    @Test
    @DisplayName("CORS 허용 출처의 공백을 제거하고 상태 비밀번호를 인코딩한다")
    void configuresCorsAndPasswordEncoding() {
        SecurityConfig config = new SecurityConfig(
                mock(CustomOAuth2UserService.class),
                mock(OAuth2LoginSuccessHandler.class),
                mock(JwtAuthenticationFilter.class),
                new MockEnvironment().withProperty("spring.profiles.active", "dev"));
        ReflectionTestUtils.setField(config, "corsAllowedOrigins", " https://app.example.com, ,https://admin.example.com ");

        CorsConfigurationSource source = config.corsConfigurationSource();
        CorsConfiguration cors = source.getCorsConfiguration(new MockHttpServletRequest("GET", "/api/public/places"));
        PasswordEncoder encoder = config.passwordEncoder();

        assertThat(cors.getAllowedOrigins()).containsExactly("https://app.example.com", "https://admin.example.com");
        assertThat(cors.getAllowedMethods()).containsExactly("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS");
        assertThat(cors.getAllowedHeaders()).containsExactly("*");
        assertThat(cors.getAllowCredentials()).isTrue();
        assertThat(encoder.matches("secure-password", encoder.encode("secure-password"))).isTrue();
    }
}
