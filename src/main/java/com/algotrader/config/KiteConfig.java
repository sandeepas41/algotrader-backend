package com.algotrader.config;

import com.zerodhatech.kiteconnect.KiteConnect;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties and bean definitions for Kite Connect API integration.
 *
 * <p>Binds to the {@code kite.*} prefix in application.properties. Provides:
 * <ul>
 *   <li>A singleton {@link KiteConnect} bean — the Kite SDK client shared across
 *       the entire application. After authentication, the access token is set on this
 *       instance and all services use the same connection.</li>
 *   <li>API key, API secret, and auto-login configuration properties.</li>
 * </ul>
 *
 * <p>Auto-login configuration ({@code kite.auto-login.*}) controls the Playwright-based
 * automated Kite OAuth flow. When enabled, the system uses headless Chromium + TOTP
 * to log in automatically on startup, eliminating the need for manual browser login.
 *
 * <p>Per the Kite SDK sample: create one KiteConnect instance, then call
 * {@code setAccessToken()}, {@code setUserId()}, {@code setPublicToken()} after login.
 */
@Configuration
@ConfigurationProperties(prefix = "kite")
@Getter
@Setter
public class KiteConfig {

    private static final Logger log = LoggerFactory.getLogger(KiteConfig.class);

    /** Kite Connect API key (from Zerodha developer console). */
    private String apiKey;

    /** Kite Connect API secret (from Zerodha developer console). */
    private String apiSecret;

    /** Auto-login configuration for Playwright-based automated OAuth login. */
    private AutoLogin autoLogin = new AutoLogin();

    /**
     * Singleton KiteConnect SDK client.
     *
     * <p>Created once with the API key. After successful authentication,
     * {@link com.algotrader.broker.KiteAuthService} sets the access token,
     * user ID, public token, and session expiry hook on this instance.
     * All broker services inject this bean for Kite API calls.
     */
    @Bean
    public KiteConnect kiteConnect() {
        log.info("Creating KiteConnect bean with API key: {}...", maskApiKey(apiKey));
        KiteConnect kiteConnect = new KiteConnect(apiKey);
        kiteConnect.setSessionExpiryHook(() -> {
            log.warn("Kite session expired (detected by SDK SessionExpiryHook)");
        });
        return kiteConnect;
    }

    private String maskApiKey(String key) {
        if (key == null || key.length() < 4) {
            return "****";
        }
        return key.substring(0, 4) + "****";
    }

    /**
     * Configuration for automated Kite login via Playwright (headless Chromium) + TOTP.
     *
     * <p>When enabled, the system automates the Kite OAuth flow on startup:
     * navigates to Kite login → enters credentials → generates TOTP → extracts request_token.
     * This eliminates the need for manual browser-based login.
     */
    @Getter
    @Setter
    public static class AutoLogin {

        /** Whether automated login via Playwright is enabled. */
        private boolean enabled = false;

        /** Zerodha user ID (e.g., "AB1234"). */
        private String userId;

        /** Zerodha account password. */
        private String password;

        /** Base32-encoded TOTP secret key for 2FA (from Zerodha's authenticator setup). */
        private String otpSecret;

        /** Whether to run Chromium in headless mode (true for servers, false for debugging). */
        private boolean headless = true;
    }
}
