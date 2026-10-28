# A tiny Spring Boot WebSocket

This project shows the smallest useful real-time message flow: two browser tabs
connect to one Spring Boot server, and a message sent by either tab is pushed
back to both. The server keeps track of open connections and broadcasts each
incoming text message.

It uses **raw WebSocket**: no STOMP, SockJS, broker, database, authentication, or
JavaScript framework. That keeps the basic connection and message flow visible.

## Run it

Requires Java 21. Maven is included as a wrapper:

```bash
./mvnw spring-boot:run
```

Open <http://localhost:8080> in two tabs. Type a name and a message in either
tab, then send it. Both tabs should show the same message. The server logs each
connection, received message, and disconnection.

Run the automated end-to-end test with:

```bash
./mvnw test
```

The test opens two real WebSocket connections, sends through one, and checks
that both clients receive the message.

## Start with the backend

### 1. Register the endpoint

`WebSocketConfig` maps the URL `/ws` to `ChatWebSocketHandler`. It is the
WebSocket equivalent of registering a route, but this route upgrades the
connection and keeps it open.

### 2. Keep track of connected clients

`ChatWebSocketHandler.afterConnectionEstablished` runs when a client connects.
It adds Spring's `WebSocketSession` to a concurrent set. When the client closes
the connection, `afterConnectionClosed` removes that session.

### 3. Receive and send messages

`ChatWebSocketHandler.handleTextMessage` runs for every text message received
from any connected client. It loops over the open sessions and sends the same
text to each one. This is the broadcast. The sender is included, so the browser
does not need to draw its own sent message separately.

The browser sends plain text such as:

```text
Alice: Hello, Bob!
```

There is no message DTO or JSON conversion in this first example. WebSocket
transports frames; this example chooses to put plain text in each frame.

## The mental model: REST vs WebSocket

With a typical REST request, a client sends an HTTP request, the server returns
an HTTP response, and that exchange is over. The client has to make another
request to ask for more data.

With WebSocket, the client first makes an HTTP request to `/ws`. The server
upgrades it to a WebSocket connection. After that, the same connection stays
open in both directions:

```text
Browser A ── connect to /ws ──► Spring Boot
Browser B ── connect to /ws ──► Spring Boot
Browser A ── "Alice: Hi" ─────► handleTextMessage
Browser A ◄── "Alice: Hi" ────┤
Browser B ◄── "Alice: Hi" ────┘
```

There is no new HTTP request for each chat message. Either side can send a
message whenever the connection is open.

## What is the browser code?

The tiny page is at `src/main/resources/static/index.html`. Spring Boot serves
files from `static` automatically, so there is no page controller or template
engine.

It is only a test client, not a frontend application:

- **HTML** provides the name and message inputs.
- **CSS** makes the page readable; it does not affect WebSocket behavior.
- **Plain JavaScript** uses the browser's built-in `WebSocket` API to connect,
  send text, and react to messages.
- There are **no npm packages, frameworks, or frontend build tools**.

The backend is the main lesson. You can ignore the CSS and follow only the
`new WebSocket(...)`, `socket.send(...)`, and `message` event in the page.

## Files to read in order

1. `src/main/java/com/example/wspoc/config/WebSocketConfig.java` — endpoint
   registration.
2. `src/main/java/com/example/wspoc/handler/ChatWebSocketHandler.java` —
   connection lifecycle and broadcast.
3. `src/test/java/com/example/wspoc/ChatIntegrationTest.java` — two-client
   backend test.
4. `src/main/resources/static/index.html` — the minimal browser client.

## What this example does not do

- Messages are not stored; a client only receives messages sent while it is
  connected.
- Every connected client receives every message. There are no rooms or private
  recipients.
- The sender name comes from the client and is not authenticated. Do not use it
  to identify users in a real application.
- The in-memory session set belongs to this one server process. Multiple
  application instances need a shared messaging layer if they must broadcast
  across instances.

Once this raw example makes sense, Spring's STOMP support can add named
destinations such as `/topic/messages`, subscriptions, and routing. Those are
useful conveniences on top of WebSocket, but are deliberately left out here.
