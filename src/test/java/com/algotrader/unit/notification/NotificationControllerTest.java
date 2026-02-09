package com.algotrader.unit.notification;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.algotrader.api.controller.NotificationController;
import com.algotrader.domain.model.NotificationPreference;
import com.algotrader.entity.NotificationPreferenceEntity;
import com.algotrader.exception.GlobalExceptionHandler;
import com.algotrader.mapper.NotificationPreferenceMapper;
import com.algotrader.notification.NotificationService;
import com.algotrader.repository.jpa.NotificationPreferenceJpaRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Unit tests for NotificationController (Task 20.1).
 *
 * <p>Verifies: list preferences, update preference, test Telegram endpoint.
 */
class NotificationControllerTest {

    private MockMvc mockMvc;

    @Mock
    private NotificationPreferenceJpaRepository notificationPreferenceJpaRepository;

    @Mock
    private NotificationPreferenceMapper notificationPreferenceMapper;

    @Mock
    private NotificationService notificationService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        NotificationController controller = new NotificationController(
                notificationPreferenceJpaRepository, notificationPreferenceMapper, notificationService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void getPreferences_returnsList() throws Exception {
        List<NotificationPreferenceEntity> entities = List.of(NotificationPreferenceEntity.builder()
                .id(1L)
                .alertType("RISK")
                .enabledChannels("WEBSOCKET,TELEGRAM")
                .enabled(true)
                .build());

        when(notificationPreferenceJpaRepository.findAll()).thenReturn(entities);
        when(notificationPreferenceMapper.toDomainList(entities))
                .thenReturn(List.of(NotificationPreference.builder()
                        .id(1L)
                        .alertType("RISK")
                        .enabledChannels("WEBSOCKET,TELEGRAM")
                        .enabled(true)
                        .build()));

        mockMvc.perform(get("/api/notifications/preferences"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].alertType").value("RISK"))
                .andExpect(jsonPath("$[0].enabled").value(true));
    }

    @Test
    void updatePreference_saves() throws Exception {
        NotificationPreferenceEntity savedEntity = NotificationPreferenceEntity.builder()
                .id(1L)
                .alertType("ORDER_FILL")
                .enabledChannels("WEBSOCKET,TELEGRAM")
                .enabled(true)
                .build();

        when(notificationPreferenceMapper.toEntity(any())).thenReturn(savedEntity);
        when(notificationPreferenceJpaRepository.save(any())).thenReturn(savedEntity);
        when(notificationPreferenceMapper.toDomain(savedEntity))
                .thenReturn(NotificationPreference.builder()
                        .id(1L)
                        .alertType("ORDER_FILL")
                        .enabledChannels("WEBSOCKET,TELEGRAM")
                        .enabled(true)
                        .build());

        mockMvc.perform(put("/api/notifications/preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {"id":1,"alertType":"ORDER_FILL","enabledChannels":"WEBSOCKET,TELEGRAM","enabled":true}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alertType").value("ORDER_FILL"))
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void testTelegram_returnsMessage() throws Exception {
        mockMvc.perform(get("/api/notifications/test/telegram"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Test notification sent"));
    }
}
