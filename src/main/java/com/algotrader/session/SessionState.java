package com.algotrader.session;

/**
 * State machine for the Kite session lifecycle.
 *
 * <p>Valid transitions:
 * <pre>
 * DISCONNECTED -> AUTHENTICATING -> CONNECTED -> ACTIVE -> EXPIRY_WARNING -> EXPIRED
 *                                                  ^                           |
 *                                                  +------ (re-auth) ----------+
 * </pre>
 *
 * <p>CONNECTED means a session was established but not yet verified by a health check.
 * ACTIVE means the session has been verified and is ready for trading operations.
 * EXPIRY_WARNING means the session will expire within 30 minutes.
 */
public enum SessionState {

    /** No session exists — user needs to authenticate. */
    DISCONNECTED,

    /** Authentication in progress (request token received, generating session). */
    AUTHENTICATING,

    /** Session established, WebSocket connected, pending first health check. */
    CONNECTED,

    /** Session verified by health check — ready to trade. */
    ACTIVE,

    /** Session will expire within 30 minutes. */
    EXPIRY_WARNING,

    /** Session has expired — all live operations blocked. */
    EXPIRED
}
