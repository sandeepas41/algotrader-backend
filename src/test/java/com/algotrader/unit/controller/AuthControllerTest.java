package com.algotrader.unit.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.algotrader.api.controller.AuthController;
import com.algotrader.broker.KiteAuthService;
import com.algotrader.config.ApiResponseAdvice;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Standalone MockMvc tests for the AuthController.
 * Uses MockMvcBuilders.standaloneSetup since @WebMvcTest was removed in Spring Boot 4.0.
 */
@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    private MockMvc mockMvc;

    @Mock
    private KiteAuthService kiteAuthService;

    @InjectMocks
    private AuthController authController;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(authController)
                .setControllerAdvice(new ApiResponseAdvice())
                .build();
    }

    @Test
    @DisplayName("GET /api/auth/login-url returns Kite OAuth login URL")
    void getLoginUrlReturnsUrl() throws Exception {
        when(kiteAuthService.getLoginUrl()).thenReturn("https://kite.zerodha.com/connect/login?v=3&api_key=abc123");

        mockMvc.perform(get("/api/auth/login-url"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.data.loginUrl").value("https://kite.zerodha.com/connect/login?v=3&api_key=abc123"));
    }

    @Test
    @DisplayName("POST /api/auth/callback handles OAuth callback with request_token in JSON body")
    void handleCallbackSucceeds() throws Exception {
        when(kiteAuthService.getCurrentUserId()).thenReturn("XY1234");
        when(kiteAuthService.getCurrentUserName()).thenReturn("Test User");
        when(kiteAuthService.getTokenExpiry()).thenReturn(LocalDateTime.of(2025, 2, 9, 6, 0, 0));

        mockMvc.perform(post("/api/auth/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestToken\":\"test-token\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value("XY1234"))
                .andExpect(jsonPath("$.data.userName").value("Test User"))
                .andExpect(jsonPath("$.data.sessionExpiresAt").exists());

        verify(kiteAuthService).handleCallback("test-token");
    }

    @Test
    @DisplayName("POST /api/auth/callback returns 400 when requestToken is missing")
    void handleCallbackReturnsBadRequestWhenTokenMissing() throws Exception {
        mockMvc.perform(post("/api/auth/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/auth/re-authenticate triggers re-authentication")
    void reAuthenticateSucceeds() throws Exception {
        mockMvc.perform(post("/api/auth/re-authenticate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.message").value("Re-authentication successful"));

        verify(kiteAuthService).reAuthenticate();
    }

    @Test
    @DisplayName("GET /api/auth/status returns authenticated status with correct field names")
    void getStatusReturnsAuthenticated() throws Exception {
        when(kiteAuthService.isAuthenticated()).thenReturn(true);
        when(kiteAuthService.getCurrentUserId()).thenReturn("XY1234");
        when(kiteAuthService.getCurrentUserName()).thenReturn("Test User");
        when(kiteAuthService.getTokenExpiry()).thenReturn(LocalDateTime.of(2025, 2, 9, 6, 0, 0));

        mockMvc.perform(get("/api/auth/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isAuthenticated").value(true))
                .andExpect(jsonPath("$.data.userId").value("XY1234"))
                .andExpect(jsonPath("$.data.userName").value("Test User"))
                .andExpect(jsonPath("$.data.sessionExpiresAt").exists());
    }

    @Test
    @DisplayName("GET /api/auth/status returns not authenticated when no session")
    void getStatusReturnsNotAuthenticated() throws Exception {
        when(kiteAuthService.isAuthenticated()).thenReturn(false);
        when(kiteAuthService.getCurrentUserId()).thenReturn(null);
        when(kiteAuthService.getCurrentUserName()).thenReturn(null);
        when(kiteAuthService.getTokenExpiry()).thenReturn(null);

        mockMvc.perform(get("/api/auth/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isAuthenticated").value(false));
    }

    @Test
    @DisplayName("POST /api/auth/logout calls service logout")
    void logoutSucceeds() throws Exception {
        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.message").value("Logged out successfully"));

        verify(kiteAuthService).logout();
    }
}
