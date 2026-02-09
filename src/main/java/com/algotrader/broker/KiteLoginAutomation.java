package com.algotrader.broker;

import com.algotrader.config.KiteConfig;
import com.algotrader.exception.BrokerException;
import com.eatthepath.otp.TimeBasedOneTimePasswordGenerator;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.security.InvalidKeyException;
import java.time.Instant;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Automates the Zerodha Kite Connect OAuth login flow using Playwright (headless Chromium).
 *
 * <p>This replaces the Node.js Puppeteer sidecar with a pure Java solution that runs
 * inside the Spring Boot process. The automation flow:
 * <ol>
 *   <li>Launches headless Chromium via Playwright</li>
 *   <li>Navigates to the Kite login page with the configured API key</li>
 *   <li>Enters the user's credentials (userId + password)</li>
 *   <li>Generates a TOTP code from the base32-encoded OTP secret</li>
 *   <li>Submits the TOTP and waits for the OAuth redirect</li>
 *   <li>Extracts the {@code request_token} from the redirect URL</li>
 * </ol>
 *
 * <p>The returned request_token is then exchanged for an access_token by
 * {@link KiteAuthService#exchangeAndSaveToken(String)}.
 *
 * <p>Requires Playwright browsers to be installed via: {@code mvn exec:java
 * -e -D exec.mainClass=com.microsoft.playwright.CLI -D exec.args="install chromium"}
 */
@Service
public class KiteLoginAutomation {

    private static final Logger log = LoggerFactory.getLogger(KiteLoginAutomation.class);

    /** Kite OAuth login URL template. */
    private static final String KITE_LOGIN_URL = "https://kite.zerodha.com/connect/login?v=3&api_key=%s";

    private final KiteConfig kiteConfig;

    public KiteLoginAutomation(KiteConfig kiteConfig) {
        this.kiteConfig = kiteConfig;
    }

    /**
     * Automates the full Kite login flow and returns the request_token.
     *
     * <p>Opens a headless Chromium browser, fills in credentials, enters TOTP,
     * and extracts the request_token from the redirect URL after successful login.
     *
     * @return the request_token from the Kite OAuth redirect
     * @throws BrokerException if any step of the automation fails
     */
    public String obtainRequestToken() {
        KiteConfig.AutoLogin autoLogin = kiteConfig.getAutoLogin();

        log.info("Starting automated Kite login for user: {}", autoLogin.getUserId());

        try (Playwright playwright = Playwright.create()) {
            BrowserType.LaunchOptions launchOptions =
                    new BrowserType.LaunchOptions().setHeadless(autoLogin.isHeadless());

            try (Browser browser = playwright.chromium().launch(launchOptions)) {
                Page page = browser.newPage();

                // Step 1: Navigate to Kite login page
                String loginUrl = String.format(KITE_LOGIN_URL, kiteConfig.getApiKey());
                log.info("Navigating to Kite login page...");
                page.navigate(loginUrl);

                // Step 2: Fill in credentials
                log.info("Filling in login credentials...");
                page.waitForSelector("input#userid", new Page.WaitForSelectorOptions().setTimeout(10000));
                page.fill("input#userid", autoLogin.getUserId());
                page.fill("input#password", autoLogin.getPassword());
                page.click("button[type='submit']");

                // Step 3: Wait for TOTP input and enter generated code
                log.info("Waiting for TOTP input field...");
                page.waitForSelector(
                        "input[type='number'], input[type='text'][autocomplete='one-time-code']",
                        new Page.WaitForSelectorOptions().setTimeout(10000));

                String totp = generateTotp(autoLogin.getOtpSecret());
                log.info("Generated TOTP code, entering...");

                // Step 4: Use waitForRequest to capture the redirect, then type the TOTP.
                // waitForRequest avoids the single-thread deadlock: it sets up a listener,
                // runs the action (typing TOTP), and then waits for the matching request.
                // The redirect URL (e.g. 127.0.0.1:3010) may not be running — doesn't matter,
                // we only need the outgoing request URL.
                log.info("Waiting for OAuth redirect...");
                com.microsoft.playwright.Request redirectRequest = page.waitForRequest(
                        request -> request.url().contains("request_token="),
                        new Page.WaitForRequestOptions().setTimeout(30000),
                        () -> {
                            page.locator("input[type='number'], input[type='text'][autocomplete='one-time-code']")
                                    .first()
                                    .pressSequentially(
                                            totp,
                                            new com.microsoft.playwright.Locator.PressSequentiallyOptions()
                                                    .setDelay(50));
                        });

                String finalUrl = redirectRequest.url();
                log.info("Intercepted redirect URL: {}", finalUrl);

                String requestToken = extractRequestToken(finalUrl);
                if (requestToken == null || requestToken.isBlank()) {
                    throw new BrokerException("request_token not found in redirect URL: " + finalUrl);
                }

                log.info("Successfully obtained request_token via automated login");
                return requestToken;
            }
        } catch (BrokerException e) {
            throw e;
        } catch (Exception e) {
            throw new BrokerException("Automated Kite login failed: " + e.getMessage(), e);
        }
    }

    /**
     * Generates a TOTP (Time-based One-Time Password) from a base32-encoded secret.
     *
     * <p>Uses the RFC 6238 standard with SHA-1 hash, 6 digits, and 30-second time step
     * (matching Zerodha's TOTP implementation).
     */
    String generateTotp(String base32Secret) {
        try {
            byte[] keyBytes = base32Decode(base32Secret);
            SecretKeySpec key = new SecretKeySpec(keyBytes, "HmacSHA1");

            TimeBasedOneTimePasswordGenerator totpGenerator = new TimeBasedOneTimePasswordGenerator();
            int otp = totpGenerator.generateOneTimePassword(key, Instant.now());

            // Pad to 6 digits (e.g., 1234 → "001234")
            return String.format("%06d", otp);
        } catch (InvalidKeyException e) {
            throw new BrokerException("Failed to generate TOTP: " + e.getMessage(), e);
        }
    }

    /**
     * Decodes a base32-encoded string to bytes.
     * Handles the standard base32 alphabet (A-Z, 2-7) with optional padding.
     */
    private byte[] base32Decode(String base32) {
        // Strip padding and convert to uppercase
        String normalized = base32.replaceAll("=", "").toUpperCase();
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

        int bitsTotal = normalized.length() * 5;
        byte[] result = new byte[bitsTotal / 8];
        int buffer = 0;
        int bitsInBuffer = 0;
        int index = 0;

        for (char c : normalized.toCharArray()) {
            int val = alphabet.indexOf(c);
            if (val < 0) {
                throw new IllegalArgumentException("Invalid base32 character: " + c);
            }
            buffer = (buffer << 5) | val;
            bitsInBuffer += 5;
            if (bitsInBuffer >= 8) {
                result[index++] = (byte) (buffer >> (bitsInBuffer - 8));
                bitsInBuffer -= 8;
                buffer &= (1 << bitsInBuffer) - 1;
            }
        }

        return result;
    }

    /** Extracts the request_token query parameter from a URL. */
    private String extractRequestToken(String url) {
        try {
            java.net.URI uri = java.net.URI.create(url);
            String query = uri.getQuery();
            if (query == null) return null;

            for (String param : query.split("&")) {
                String[] parts = param.split("=", 2);
                if (parts.length == 2 && "request_token".equals(parts[0])) {
                    return java.net.URLDecoder.decode(parts[1], java.nio.charset.StandardCharsets.UTF_8);
                }
            }
            return null;
        } catch (Exception e) {
            log.error("Failed to parse redirect URL: {}", url, e);
            return null;
        }
    }
}
