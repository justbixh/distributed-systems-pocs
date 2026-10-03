package com.example.wspoc.controller;

import com.example.wspoc.model.ChatMessage;
import com.example.wspoc.model.ChatMessage.MessageType;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.security.Principal;

/**
 * STOMP message handler demonstrating three common patterns:
 *
 * <ol>
 *   <li><b>Broadcast</b>     – {@code /app/chat.send}   → published to {@code /topic/messages}</li>
 *   <li><b>Join event</b>    – {@code /app/chat.join}   → published to {@code /topic/messages}</li>
 *   <li><b>Private (P2P)</b> – {@code /app/chat.private}→ routed to {@code /user/{username}/queue/private}</li>
 * </ol>
 */
@Controller
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    /** Used for point-to-point / user-targeted messages. */
    private final SimpMessagingTemplate messagingTemplate;

    public ChatController(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    // -------------------------------------------------------------------------
    // 1. Broadcast – all subscribers on /topic/messages receive this
    // -------------------------------------------------------------------------

    /**
     * Maps STOMP frames sent to {@code /app/chat.send}.
     * The return value is auto-published to {@code /topic/messages}.
     */
    @MessageMapping("/chat.send")
    @SendTo("/topic/messages")
    public ChatMessage handleBroadcast(@Payload ChatMessage message) {
        log.info("Broadcast from={} content={}", message.from(), message.content());
        // Re-create to stamp server-side timestamp
        return ChatMessage.of(message.from(), null, message.content(), MessageType.CHAT);
    }

    // -------------------------------------------------------------------------
    // 2. Join notification
    // -------------------------------------------------------------------------

    /**
     * Intercepts the user's username from the STOMP session and broadcasts
     * a JOIN notification to all subscribers.
     */
    @MessageMapping("/chat.join")
    @SendTo("/topic/messages")
    public ChatMessage handleJoin(@Payload ChatMessage message, SimpMessageHeaderAccessor headerAccessor) {
        String username = message.from();
        // Store username in session for later use (e.g., disconnect events)
        headerAccessor.getSessionAttributes().put("username", username);
        log.info("User joined: {}", username);
        return ChatMessage.of(username, null, username + " joined the chat!", MessageType.JOIN);
    }

    // -------------------------------------------------------------------------
    // 3. Private / point-to-point message
    // -------------------------------------------------------------------------

    /**
     * Routes a private message directly to a specific user's personal queue.
     * The target user must be subscribed to {@code /user/queue/private}.
     *
     * <p>No {@code @SendTo} here – we use {@link SimpMessagingTemplate} for
     * user-targeted delivery.
     */
    @MessageMapping("/chat.private")
    public void handlePrivate(@Payload ChatMessage message, Principal principal) {
        String sender = (principal != null) ? principal.getName() : message.from();
        String recipient = message.to();
        log.info("Private msg from={} to={}", sender, recipient);

        // Deliver to recipient's personal queue
        messagingTemplate.convertAndSendToUser(
                recipient,
                "/queue/private",
                ChatMessage.of(sender, recipient, message.content(), MessageType.PRIVATE)
        );

        // Echo back to sender so they see their own message in the UI
        messagingTemplate.convertAndSendToUser(
                sender,
                "/queue/private",
                ChatMessage.of(sender, recipient, "[to " + recipient + "] " + message.content(), MessageType.PRIVATE)
        );
    }
}
