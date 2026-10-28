package com.example.wspoc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatIntegrationTest {

    @LocalServerPort
    private int port;

    private final List<WebSocket> sockets = new ArrayList<>();

    @Test
    void rootServesTheBrowserClient() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("Spring Boot WebSocket demo", "new WebSocket", "/ws");
    }

    @AfterEach
    void closeConnections() throws Exception {
        for (WebSocket socket : sockets) {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "test finished").get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void messageIsBroadcastToEveryConnectedClient() throws Exception {
        BlockingQueue<String> aliceMessages = new LinkedBlockingQueue<>();
        BlockingQueue<String> bobMessages = new LinkedBlockingQueue<>();
        WebSocket alice = connect(aliceMessages);
        connect(bobMessages);

        alice.sendText("Alice: Hello, Bob!", true).get(5, TimeUnit.SECONDS);

        assertThat(aliceMessages.poll(5, TimeUnit.SECONDS)).isEqualTo("Alice: Hello, Bob!");
        assertThat(bobMessages.poll(5, TimeUnit.SECONDS)).isEqualTo("Alice: Hello, Bob!");
    }

    private WebSocket connect(BlockingQueue<String> messages) throws Exception {
        WebSocket socket = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + "/ws"), new MessageListener(messages))
                .get(5, TimeUnit.SECONDS);
        sockets.add(socket);
        return socket;
    }

    private static class MessageListener implements WebSocket.Listener {

        private final BlockingQueue<String> messages;
        private final StringBuilder currentMessage = new StringBuilder();

        private MessageListener(BlockingQueue<String> messages) {
            this.messages = messages;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletableFuture<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            currentMessage.append(data);
            if (last) {
                messages.offer(currentMessage.toString());
                currentMessage.setLength(0);
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }
    }
}
