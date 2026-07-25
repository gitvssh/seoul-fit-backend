package com.seoulfit.backend.trigger.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.seoulfit.backend.config.TestSecurityConfig;
import com.seoulfit.backend.config.WithMockCustomUser;
import com.seoulfit.backend.trigger.application.port.in.EvaluateTriggerUseCase;
import com.seoulfit.backend.trigger.application.port.in.dto.LocationTriggerCommand;
import com.seoulfit.backend.trigger.application.port.in.dto.TriggerEvaluationResult;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TriggerController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TestSecurityConfig.class)
@ActiveProfiles("test")
@WithMockCustomUser(id = 42L)
class TriggerControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private EvaluateTriggerUseCase evaluateTriggerUseCase;

    @Test
    @DisplayName("위치 트리거는 요청 userId를 무시하고 인증 사용자를 사용한다")
    void evaluatesForAuthenticatedUser() throws Exception {
        when(evaluateTriggerUseCase.evaluateLocationBasedTriggers(any()))
                .thenReturn(TriggerEvaluationResult.builder()
                        .triggered(false)
                        .triggeredCount(0)
                        .totalEvaluated(1)
                        .triggeredList(List.of())
                        .build());

        mockMvc.perform(post("/api/triggers/evaluate/location")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId": "999",
                                  "latitude": 37.5665,
                                  "longitude": 126.978,
                                  "radius": 2000
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.triggered").value(false));

        ArgumentCaptor<LocationTriggerCommand> command =
                ArgumentCaptor.forClass(LocationTriggerCommand.class);
        verify(evaluateTriggerUseCase).evaluateLocationBasedTriggers(command.capture());
        assertThat(command.getValue().getUserId()).isEqualTo("42");
    }

    @Test
    @DisplayName("유효하지 않은 좌표는 거부한다")
    void rejectsInvalidCoordinates() throws Exception {
        mockMvc.perform(post("/api/triggers/evaluate/location")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\":91,\"longitude\":126.978}"))
                .andExpect(status().isBadRequest());
    }
}
