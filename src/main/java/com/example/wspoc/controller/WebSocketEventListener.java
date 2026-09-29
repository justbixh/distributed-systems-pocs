package com.example.wspoc.controller;

import com.example.wspoc.model.ChatMessage;
import com.example.wspoc.model.ChatMessage.MessageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/**
 * Listens to WebSocket lifecycle events and notifies all subscribers
 * when a user connects or disconnects.
 */
@Component
public class WebSocketEventListener {

    private static final Logger log = LoggerFactory.getLogger(WebSocketEventListener.class);

    private final SimpMessagingTemplate messagingTemplate;

    public WebSocketEventListener(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    /** Fired after a STOMP CONNECTED frame is acknowledged. */
    @EventListener
    public void handleWebSocketConnectListener(SessionConnectedEvent event) {
        log.info("New WebSocket session connected: {}", event.getMessage().getHeaders().get("simpSessionId"));
    }

    /**
     * Fired when a client disconnects or the session times out.
     * Broadcasts a LEAVE notification to all remaining subscribers.
     */
    @EventListener
    public void handleWebSocketDisconnectListener(SessionDisconnectEvent event) {
        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        String username = (String) headerAccessor.getSessionAttributes().get("username");

        if (username != null) {
            log.info("User disconnected: {}", username);
            messagingTemplate.convertAndSend(
                    "/topic/messages",
                    ChatMessage.of(username, null, username + " left the chat.", MessageType.LEAVE)
            );
        }
    }
}
