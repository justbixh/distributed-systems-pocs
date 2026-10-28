package com.example.wspoc.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * WebSocket / STOMP message broker configuration.
 *
 * <pre>
 * Connection flow:
 *   1. Client connects to STOMP endpoint  →  ws://localhost:8080/ws
 *   2. Client subscribes to a topic       →  /topic/messages
 *   3. Client sends a message             →  /app/chat.send  or  /app/chat.private
 *   4. Server broadcasts / routes reply
 * </pre>
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /**
     * Register the STOMP endpoint that clients connect to.
     * {@code withSockJS()} adds a SockJS fallback for environments that
     * don't support raw WebSockets (e.g. some corporate proxies).
     */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")   // tighten this in production
                .withSockJS();
    }

    /**
     * Configure the in-memory message broker.
     *
     * <ul>
     *   <li>{@code /topic} – broadcast (fan-out) channel</li>
     *   <li>{@code /queue} – point-to-point channel</li>
     *   <li>{@code /app}   – prefix for messages routed to {@code @MessageMapping} controllers</li>
     * </ul>
     */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");   // enables /user/{id}/queue/... routing
    }
}
