package com.algotrader.exception;

/**
 * Thrown when a live trading operation is attempted while the Kite session
 * is expired or disconnected.
 *
 * <p>Operations guarded by {@link com.algotrader.session.SessionGuard} throw
 * this exception when the session is not active. Paper trading and read-only
 * operations should not be affected.
 */
public class SessionExpiredException extends RuntimeException {

    public SessionExpiredException(String message) {
        super(message);
    }
}
