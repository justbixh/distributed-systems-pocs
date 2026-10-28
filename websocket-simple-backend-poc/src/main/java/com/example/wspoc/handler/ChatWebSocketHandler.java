package com.example.wspoc.handler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);
    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        log.info("Connected session={} activeSessions={}", session.getId(), sessions.size());
    }

    @Override
    protected void handleTextMessage(WebSocketSession sender, TextMessage message) throws IOException {
        log.info("Received session={} message={}", sender.getId(), message.getPayload());

        TextMessage reply = new TextMessage(message.getPayload());
        for (WebSocketSession recipient : sessions) {
            synchronized (recipient) {
                if (recipient.isOpen()) {
                    recipient.sendMessage(reply);
                }
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        log.info("Disconnected session={} activeSessions={}", session.getId(), sessions.size());
    }
}
