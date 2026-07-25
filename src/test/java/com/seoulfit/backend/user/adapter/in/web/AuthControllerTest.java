package com.seoulfit.backend.user.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.seoulfit.backend.config.TestSecurityConfig;
import com.seoulfit.backend.user.adapter.out.persistence.UserPort;
import com.seoulfit.backend.user.application.port.in.AuthenticateUserUseCase;
import com.seoulfit.backend.user.application.port.in.dto.OAuthAuthorizationCommand;
import com.seoulfit.backend.user.application.port.in.dto.TokenResult;
import com.seoulfit.backend.user.application.service.OAuthService;
import com.seoulfit.backend.user.infrastructure.jwt.JwtTokenProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@DisplayName("AuthController 보안 테스트")
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthenticateUserUseCase authenticateUserUseCase;

    @MockitoBean
    private OAuthService oAuthService;

    @MockitoBean
    private UserPort userPort;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @Test
    @DisplayName("일회용 OAuth 인가코드로만 로그인한다")
    void loginWithAuthorizationCode() throws Exception {
        given(authenticateUserUseCase.oauthLoginWithAuthorizationCode(any()))
                .willReturn(TokenResult.builder()
                        .accessToken("access")
                        .refreshToken("refresh")
                        .userId(1L)
                        .nickname("테스터")
                        .build());

        mockMvc.perform(post("/api/auth/oauth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "provider": "KAKAO",
                                  "authorizationCode": "one-time-code",
                                  "redirectUri": "https://example.com/auth/callback"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access"))
                .andExpect(jsonPath("$.user.id").value(1L));

        verify(authenticateUserUseCase).oauthLoginWithAuthorizationCode(
                any(OAuthAuthorizationCommand.class));
    }

    @Test
    @DisplayName("OAuth ID만 보낸 레거시 로그인은 거부한다")
    void rejectsLegacyOAuthIdLogin() throws Exception {
        mockMvc.perform(post("/api/auth/oauth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "provider": "KAKAO",
                                  "oauthUserId": "forged-id"
                                }
                                """))
                .andExpect(status().isBadRequest());

        verify(authenticateUserUseCase, never())
                .oauthLoginWithAuthorizationCode(any());
    }

    @Test
    @DisplayName("검증되지 않은 가입 및 위치 로그인 엔드포인트는 존재하지 않는다")
    void removedUnsafeTokenIssuers() throws Exception {
        mockMvc.perform(post("/api/auth/oauth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/auth/login/location")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }
}
