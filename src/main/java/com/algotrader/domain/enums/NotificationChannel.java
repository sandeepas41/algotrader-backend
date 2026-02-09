package com.algotrader.domain.enums;

/**
 * Delivery channels for alerts and notifications.
 * WEBSOCKET is real-time push to the frontend. TELEGRAM is for urgent
 * alerts when the trader may not be watching the dashboard.
 */
public enum NotificationChannel {
    WEBSOCKET,
    TELEGRAM,
    EMAIL
}
