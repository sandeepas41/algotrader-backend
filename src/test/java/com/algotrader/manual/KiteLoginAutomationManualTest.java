package com.algotrader.manual;

import static org.assertj.core.api.Assertions.assertThat;

import com.algotrader.broker.KiteLoginAutomation;
import com.algotrader.config.KiteConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manual test for the Kite Playwright login automation.
 *
 * <p>Runs the full browser-based login flow (non-headless) using credentials from {@code .env}.
 * Tagged as "manual" so it never runs with {@code mvn test}. Run it explicitly:
 * <pre>
 *   ./mvnw test -Dgroups=manual -Dtest=KiteLoginAutomationManualTest
 * </pre>
 * Or run directly from your IDE by right-clicking the test method.
 *
 * <p>No Spring context, no DB, no Redis — just Playwright + .env credentials.
 */
@Tag("manual")
class KiteLoginAutomationManualTest {

    private static final Logger log = LoggerFactory.getLogger(KiteLoginAutomationManualTest.class);

    @Test
    void testObtainRequestToken() {
        Map<String, String> env = loadEnvFile();

        KiteConfig kiteConfig = new KiteConfig();
        kiteConfig.setApiKey(env.get("KITE_API_KEY"));
        kiteConfig.setApiSecret(env.get("KITE_API_SECRET"));

        KiteConfig.AutoLogin autoLogin = new KiteConfig.AutoLogin();
        autoLogin.setEnabled(true);
        autoLogin.setUserId(env.get("KITE_USERID"));
        autoLogin.setPassword(env.get("KITE_PASSWORD"));
        autoLogin.setOtpSecret(env.get("KITE_OTP_SECRET_KEY"));
        autoLogin.setHeadless(false); // Non-headless so we can watch
        kiteConfig.setAutoLogin(autoLogin);

        log.info("API Key: {}****, User: {}", kiteConfig.getApiKey().substring(0, 4), autoLogin.getUserId());

        KiteLoginAutomation kiteLoginAutomation = new KiteLoginAutomation(kiteConfig);
        String requestToken = kiteLoginAutomation.obtainRequestToken();

        log.info(">>> request_token: {}", requestToken);
        assertThat(requestToken).isNotBlank();
    }

    /**
     * Reads key=value pairs from the project root .env file.
     * Skips blank lines and comments (#).
     */
    private Map<String, String> loadEnvFile() {
        Path envPath = Path.of("").toAbsolutePath().resolve(".env");
        log.info("Loading .env from: {}", envPath);

        Map<String, String> env = new HashMap<>();
        try {
            for (String line : Files.readAllLines(envPath)) {
                String trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                int eq = trimmed.indexOf('=');
                if (eq > 0) {
                    env.put(
                            trimmed.substring(0, eq).strip(),
                            trimmed.substring(eq + 1).strip());
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to read .env file at " + envPath, e);
        }
        return env;
    }
}
