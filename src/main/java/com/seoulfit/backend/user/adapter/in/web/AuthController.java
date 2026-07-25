package com.seoulfit.backend.user.adapter.in.web;

import com.seoulfit.backend.user.adapter.in.web.dto.OAuthLoginRequest;
import com.seoulfit.backend.user.adapter.in.web.dto.TokenResponse;
import com.seoulfit.backend.user.adapter.out.persistence.UserPort;
import com.seoulfit.backend.user.application.port.in.AuthenticateUserUseCase;
import com.seoulfit.backend.user.application.port.in.dto.OAuthAuthorizationCommand;
import com.seoulfit.backend.user.application.port.in.dto.TokenResult;
import com.seoulfit.backend.user.application.service.OAuthService;
import com.seoulfit.backend.user.domain.User;
import com.seoulfit.backend.user.infrastructure.jwt.JwtTokenProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 인증 컨트롤러
 * <p>
 * 헥사고날 아키텍처의 입력 어댑터 사용자 인증과 관련된 HTTP 요청을 처리 카카오 공식 문서 기준으로 최신 OAuth 2.0 Authorization Code Flow 지원
 *
 * @author Seoul Fit
 * @since 1.0.0
 */
@Tag(name = "인증", description = "사용자 인증 및 OAuth 관련 API")
@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "사용자 인증 API - 카카오 공식 문서 기준")
public class AuthController {

    private final AuthenticateUserUseCase authenticateUserUseCase;
    private final OAuthService oAuthService;
    private final UserPort userPort;
    private final JwtTokenProvider jwtTokenProvider;

    @Operation(
            summary = "OAuth 로그인 (Authorization Code Flow)",
            description = "OAuth 제공자가 발급한 일회용 권한부여 승인코드를 검증해 로그인하거나 가입합니다."
    )
    @PostMapping("/oauth/login")
    public ResponseEntity<TokenResponse> oauthLogin(@Valid @RequestBody OAuthLoginRequest request) {
        log.info("OAuth 로그인 요청: provider={}", request.getProvider());

        if (request.getAuthorizationCode() == null || request.getRedirectUri() == null) {
            throw new IllegalArgumentException(
                    "authorizationCode와 redirectUri가 필요합니다. 기존 oauthUserId 로그인은 지원하지 않습니다.");
        }

        OAuthAuthorizationCommand command = OAuthAuthorizationCommand.of(
                request.getProvider(),
                request.getAuthorizationCode(),
                request.getRedirectUri()
        );
        TokenResult result = authenticateUserUseCase.oauthLoginWithAuthorizationCode(command);
        return ResponseEntity.ok(TokenResponse.from(result));
    }

    @Operation(summary = "토큰 갱신", description = "리프레시 토큰으로 새로운 액세스 토큰을 발급합니다.")
    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refreshToken(
            @RequestHeader("Refresh-Token") String refreshToken) {
        TokenResult result = authenticateUserUseCase.refreshToken(refreshToken);
        return ResponseEntity.ok(TokenResponse.from(result));
    }

    @Operation(
            summary = "OAuth 로그아웃",
            description = "OAuth 제공자에서 로그아웃 처리합니다. JWT 토큰으로 사용자를 조회하여 저장된 OAuth AccessToken을 사용합니다."
    )
    @PostMapping("/oauth/logout")
    public ResponseEntity<Map<String, Object>> oauthLogout(
            @Parameter(description = "사용자 JWT 토큰") @RequestHeader("Authorization") String authHeader) {

        try {
            // JWT 토큰에서 Bearer 제거
            String jwtToken = authHeader.replace("Bearer ", "");

            // JWT에서 사용자 ID 추출
            Long userId = jwtTokenProvider.getUserIdFromToken(jwtToken);

            // 사용자 조회
            User user = userPort.findById(userId)
                    .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

            // OAuth 사용자가 아닌 경우
            if (!user.isOAuthUser()) {
                return ResponseEntity.ok(Map.of("result", "OAuth 사용자가 아닙니다."));
            }

            // 유효한 OAuth 토큰이 없는 경우
            if (!user.hasValidOAuthToken()) {
                return ResponseEntity.ok(Map.of("result", "유효한 OAuth 토큰이 없습니다."));
            }

            // OAuth Provider 로그아웃 수행
            Map<String, Object> result = oAuthService.logout(user.getOauthProvider(), user.getOauthAccessToken());

            // 사용자의 OAuth 토큰 제거
            user.clearOAuthToken();
            userPort.save(user);

            result.put("message", "로그아웃이 완료되었습니다.");
            return ResponseEntity.ok(result);

        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("OAuth 로그아웃 실패: {}", e.getMessage(), e);
            return ResponseEntity.ok(Map.of("result", "로그아웃 처리 중 오류가 발생했습니다."));
        }
    }

    @Operation(
            summary = "OAuth 연결 해제",
            description = "OAuth 제공자와의 연결을 해제합니다. JWT 토큰으로 사용자를 조회하여 저장된 OAuth AccessToken을 사용합니다."
    )
    @PostMapping("/oauth/unlink")
    public ResponseEntity<Map<String, Object>> oauthUnlink(
            @Parameter(description = "사용자 JWT 토큰") @RequestHeader("Authorization") String authHeader) {

        try {
            // JWT 토큰에서 Bearer 제거
            String jwtToken = authHeader.replace("Bearer ", "");

            // JWT에서 사용자 ID 추출
            Long userId = jwtTokenProvider.getUserIdFromToken(jwtToken);

            // 사용자 조회
            User user = userPort.findById(userId)
                    .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

            // OAuth 사용자가 아닌 경우
            if (!user.isOAuthUser()) {
                return ResponseEntity.ok(Map.of("result", "OAuth 사용자가 아닙니다."));
            }

            // 유효한 OAuth 토큰이 없는 경우
            if (!user.hasValidOAuthToken()) {
                return ResponseEntity.ok(Map.of("result", "유효한 OAuth 토큰이 없습니다."));
            }

            // OAuth Provider 연결 해제 수행
            Map<String, Object> result = oAuthService.unlink(user.getOauthProvider(), user.getOauthAccessToken());

            // 사용자 계정 비활성화 또는 삭제
            user.delete(); // 또는 user.deactivate();
            user.clearOAuthToken();
            userPort.save(user);

            result.put("message", "연결 해제가 완료되었습니다.");
            return ResponseEntity.ok(result);

        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("OAuth 연결 해제 실패: {}", e.getMessage(), e);
            return ResponseEntity.ok(Map.of("result", "연결 해제 처리 중 오류가 발생했습니다."));
        }
    }
}
