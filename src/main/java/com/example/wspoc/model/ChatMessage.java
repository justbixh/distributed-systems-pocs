package com.example.wspoc.model;

import java.time.Instant;

/**
 * Payload exchanged over the STOMP channel.
 *
 * @param from      sender name / user-id
 * @param to        recipient name (null → broadcast to everyone)
 * @param content   message text
 * @param type      {@link MessageType} discriminates control vs data frames
 * @param timestamp ISO-8601 server-side timestamp (set by the controller)
 */
public record ChatMessage(
        String from,
        String to,
        String content,
        MessageType type,
        Instant timestamp
) {

    /** Convenience factory – sets server timestamp automatically. */
    public static ChatMessage of(String from, String to, String content, MessageType type) {
        return new ChatMessage(from, to, content, type, Instant.now());
    }

    public enum MessageType {
        /** Normal chat text. */
        CHAT,
        /** User joined the room. */
        JOIN,
        /** User left the room. */
        LEAVE,
        /** Private / direct message. */
        PRIVATE
    }
}
