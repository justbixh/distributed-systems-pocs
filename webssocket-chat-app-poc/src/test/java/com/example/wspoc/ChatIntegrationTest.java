package com.example.wspoc;

import com.example.wspoc.model.ChatMessage;
import com.example.wspoc.model.ChatMessage.MessageType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.Transport;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

import java.lang.reflect.Type;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the WebSocket / STOMP chat endpoints.
 *
 * <p>Each test spins up the full Spring Boot application on a random port
 * and exercises the real WebSocket stack end-to-end.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatIntegrationTest {

    @LocalServerPort
    private int port;

    private WebSocketStompClient stompClient;

    @BeforeEach
    void setUp() {
        // Build a SockJS-backed STOMP client (mirrors what the browser does)
        List<Transport> transports = List.of(new WebSocketTransport(new StandardWebSocketClient()));
        SockJsClient sockJsClient = new SockJsClient(transports);

        stompClient = new WebSocketStompClient(sockJsClient);

        // Register JavaTimeModule so java.time.Instant round-trips correctly
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        MappingJackson2MessageConverter converter = new MappingJackson2MessageConverter();
        converter.setObjectMapper(mapper);
        stompClient.setMessageConverter(converter);
    }

    @AfterEach
    void tearDown() {
        stompClient.stop();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helper – blocks until a STOMP session is established
    // ─────────────────────────────────────────────────────────────────────────
    private StompSession connect(String url) throws Exception {
        return stompClient
                .connectAsync(url, new StompSessionHandlerAdapter() {})
                .get(5, TimeUnit.SECONDS);
    }

    private String wsUrl() {
        return "ws://localhost:" + port + "/ws";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 1 – Broadcast message
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void broadcastMessage_shouldBeReceivedByAllSubscribers() throws Exception {
        BlockingQueue<ChatMessage> received = new LinkedBlockingQueue<>();

        StompSession session = connect(wsUrl());
        session.subscribe("/topic/messages", new StompFrameHandler() {
            @Override public Type getPayloadType(StompHeaders headers) { return ChatMessage.class; }
            @Override public void handleFrame(StompHeaders headers, Object payload) {
                received.offer((ChatMessage) payload);
            }
        });

        ChatMessage msg = ChatMessage.of("Alice", null, "Hello, World!", MessageType.CHAT);
        session.send("/app/chat.send", msg);

        ChatMessage reply = received.poll(5, TimeUnit.SECONDS);
        assertThat(reply).isNotNull();
        assertThat(reply.content()).isEqualTo("Hello, World!");
        assertThat(reply.from()).isEqualTo("Alice");
        assertThat(reply.type()).isEqualTo(MessageType.CHAT);
        assertThat(reply.timestamp()).isNotNull();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 2 – Join notification
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void joinMessage_shouldBroadcastJoinNotification() throws Exception {
        BlockingQueue<ChatMessage> received = new LinkedBlockingQueue<>();

        StompSession session = connect(wsUrl());
        session.subscribe("/topic/messages", new StompFrameHandler() {
            @Override public Type getPayloadType(StompHeaders headers) { return ChatMessage.class; }
            @Override public void handleFrame(StompHeaders headers, Object payload) {
                received.offer((ChatMessage) payload);
            }
        });

        session.send("/app/chat.join", ChatMessage.of("Bob", null, "", MessageType.JOIN));

        ChatMessage notification = received.poll(5, TimeUnit.SECONDS);
        assertThat(notification).isNotNull();
        assertThat(notification.type()).isEqualTo(MessageType.JOIN);
        assertThat(notification.from()).isEqualTo("Bob");
        assertThat(notification.content()).contains("Bob");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 3 – Server-push via SimpMessagingTemplate
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Verifies that the server can push messages directly to connected clients
     * using {@link org.springframework.messaging.simp.SimpMessagingTemplate}.
     * This mirrors what happens internally when a private message is delivered.
     */
    @Test
    void serverPush_viaTemplate_shouldBeReceivedBySubscriber(
            @org.springframework.beans.factory.annotation.Autowired
            org.springframework.messaging.simp.SimpMessagingTemplate template
    ) throws Exception {

        BlockingQueue<ChatMessage> topicReceived = new LinkedBlockingQueue<>();

        StompSession session = connect(wsUrl());

        // Subscribe BEFORE the push to avoid the race condition
        session.subscribe("/topic/messages", new StompFrameHandler() {
            @Override public Type getPayloadType(StompHeaders headers) { return ChatMessage.class; }
            @Override public void handleFrame(StompHeaders headers, Object payload) {
                topicReceived.offer((ChatMessage) payload);
            }
        });
        Thread.sleep(400);   // ensure SUBSCRIBE frame is processed server-side

        // Push directly from the server (simulates internal notification system)
        template.convertAndSend("/topic/messages",
                ChatMessage.of("Server", null, "Server-pushed notification", MessageType.CHAT));

        ChatMessage msg = topicReceived.poll(5, TimeUnit.SECONDS);
        assertThat(msg).isNotNull();
        assertThat(msg.from()).isEqualTo("Server");
        assertThat(msg.content()).isEqualTo("Server-pushed notification");
        assertThat(msg.type()).isEqualTo(MessageType.CHAT);
        assertThat(msg.timestamp()).isNotNull();

        session.disconnect();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 4 – Multiple subscribers receive the same broadcast
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void broadcast_shouldFanOutToAllSubscribers() throws Exception {
        BlockingQueue<ChatMessage> receivedByA = new LinkedBlockingQueue<>();
        BlockingQueue<ChatMessage> receivedByB = new LinkedBlockingQueue<>();

        StompSession sessionA = connect(wsUrl());
        StompSession sessionB = connect(wsUrl());

        sessionA.subscribe("/topic/messages", new StompFrameHandler() {
            @Override public Type getPayloadType(StompHeaders h) { return ChatMessage.class; }
            @Override public void handleFrame(StompHeaders h, Object p) { receivedByA.offer((ChatMessage) p); }
        });
        sessionB.subscribe("/topic/messages", new StompFrameHandler() {
            @Override public Type getPayloadType(StompHeaders h) { return ChatMessage.class; }
            @Override public void handleFrame(StompHeaders h, Object p) { receivedByB.offer((ChatMessage) p); }
        });

        sessionA.send("/app/chat.send", ChatMessage.of("Alice", null, "Fanout!", MessageType.CHAT));

        assertThat(receivedByA.poll(5, TimeUnit.SECONDS)).isNotNull();
        assertThat(receivedByB.poll(5, TimeUnit.SECONDS)).isNotNull();

        sessionA.disconnect();
        sessionB.disconnect();
    }
}
