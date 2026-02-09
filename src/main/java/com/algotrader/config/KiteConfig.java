package com.algotrader.config;

import com.zerodhatech.kiteconnect.KiteConnect;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Configuration properties and bean definitions for Kite Connect API integration.
 *
 * <p>Binds to the {@code kite.*} prefix in application.properties. Provides:
 * <ul>
 *   <li>A singleton {@link KiteConnect} bean — the Kite SDK client shared across
 *       the entire application. After authentication, the access token is set on this
 *       instance and all services use the same connection.</li>
 *   <li>A {@link RestClient} bean for calling the kite-login sidecar at localhost:3010.</li>
 *   <li>API key, API secret, and sidecar configuration properties.</li>
 * </ul>
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

    /** Sidecar configuration for automated Puppeteer-based OAuth login. */
    private Sidecar sidecar = new Sidecar();

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

    /**
     * RestClient configured for the kite-login sidecar HTTP calls.
     * Used by KiteAuthService for automated OAuth token acquisition.
     */
    @Bean
    public RestClient sidecarRestClient() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(sidecar.getConnectTimeout());
        requestFactory.setReadTimeout(sidecar.getReadTimeout());
        return RestClient.builder()
                .baseUrl(sidecar.getUrl())
                .requestFactory(requestFactory)
                .build();
    }

    private String maskApiKey(String key) {
        if (key == null || key.length() < 4) {
            return "****";
        }
        return key.substring(0, 4) + "****";
    }

    @Getter
    @Setter
    public static class Sidecar {

        /** Base URL of the kite-login sidecar. */
        private String url = "http://localhost:3010";

        /** Whether the sidecar auto-login is enabled on startup. */
        private boolean enabled = false;

        /** HTTP connect timeout in milliseconds for sidecar calls. */
        private int connectTimeout = 5000;

        /** HTTP read timeout in milliseconds for sidecar calls. */
        private int readTimeout = 30000;
    }
}
