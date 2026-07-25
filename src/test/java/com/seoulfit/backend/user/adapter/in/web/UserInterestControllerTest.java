package com.seoulfit.backend.user.adapter.in.web;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.seoulfit.backend.config.TestSecurityConfig;
import com.seoulfit.backend.config.WithMockCustomUser;
import com.seoulfit.backend.user.adapter.out.persistence.UserInterestPort;
import com.seoulfit.backend.user.adapter.out.persistence.UserPort;
import com.seoulfit.backend.user.domain.InterestCategory;
import com.seoulfit.backend.user.domain.User;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(UserInterestController.class)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@WithMockCustomUser(id = 1L)
@DisplayName("UserInterestController 보안 테스트")
class UserInterestControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserPort userPort;

    @MockitoBean
    private UserInterestPort userInterestPort;

    @Test
    @DisplayName("관심사 조회는 요청 본문이 아니라 인증 주체를 사용한다")
    void getInterestsUsesAuthenticatedUser() throws Exception {
        User user = authenticatedUser();
        given(userPort.findById(1L)).willReturn(Optional.of(user));
        given(userInterestPort.findInterestsByUserId(1L))
                .willReturn(List.of(InterestCategory.SPORTS));

        mockMvc.perform(post("/api/users/interests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(1L));

        verify(userPort).findById(1L);
        verify(userPort, never()).findById(999L);
    }

    @Test
    @DisplayName("관심사 변경의 위조 userId는 무시한다")
    void updateInterestsIgnoresForgedUserId() throws Exception {
        User user = authenticatedUser();
        given(userPort.findById(1L)).willReturn(Optional.of(user));

        mockMvc.perform(put("/api/users/interests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId": 999,
                                  "interests": ["SPORTS", "CULTURE"]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(1L));

        verify(userPort).findById(1L);
        verify(userPort, never()).findById(999L);
        verify(userInterestPort).deleteByUser(user);
        verify(userInterestPort).saveAll(any());
    }

    private User authenticatedUser() {
        User user = User.createLocalUser("tester@example.com", "encoded", "테스터");
        ReflectionTestUtils.setField(user, "id", 1L);
        return user;
    }
}
