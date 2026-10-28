# 🚀 WebSocket POC – Spring Boot 4 + STOMP

A production-ready Proof-of-Concept demonstrating **WebSocket / STOMP messaging** with **Spring Boot 4** (Spring Framework 7, Jakarta EE 11, Java 21).

---

## 📐 Architecture

```
Browser (SockJS + @stomp/stompjs)
        │
        │  WebSocket / HTTP fallback
        ▼
┌────────────────────────────────────────────────┐
│             Spring Boot 4 App                  │
│                                                │
│  /ws  ──► WebSocketConfig (STOMP endpoint)     │
│                                                │
│  Destinations handled by ChatController:       │
│  /app/chat.send    → broadcast /topic/messages │
│  /app/chat.join    → broadcast /topic/messages │
│  /app/chat.private → /user/{id}/queue/private  │
│                                                │
│  WebSocketEventListener:                       │
│   SessionConnectedEvent  → log                 │
│   SessionDisconnectEvent → broadcast LEAVE     │
└────────────────────────────────────────────────┘
```

### Key classes

| Class | Role |
|---|---|
| `WebSocketConfig` | Registers `/ws` STOMP endpoint (with SockJS fallback); configures `/topic`, `/queue`, `/app` prefixes |
| `ChatController` | `@MessageMapping` handlers for broadcast, join, and private DM |
| `WebSocketEventListener` | Listens to Spring `SessionConnectedEvent` / `SessionDisconnectEvent` |
| `ChatMessage` | Immutable Java record (CHAT / JOIN / LEAVE / PRIVATE) |
| `PageController` | Serves the browser chat client at `GET /` |

---

## 🔧 Prerequisites

| Tool | Version |
|---|---|
| **Java (JDK)** | 21+ (via [SDKMAN](https://sdkman.io), Homebrew, or Temurin) |
| **Maven** | Not required – `./mvnw` wrapper is included |
| **Network** | Port **8080** free |

> **Using SDKMAN?**
> ```bash
> sdk install java 21.0.2-tem   # or any Java 21 distribution
> sdk use java 21.0.2-tem
> ```

---

## 🏃 Running the Application

```bash
# 1. Clone / enter the project directory
cd ws-poc

# 2. Build & run (first run downloads Maven ~10 MB)
./mvnw spring-boot:run
```

The app starts on **http://localhost:8080**.  
Open it in **two browser tabs** to see real-time messaging in action.

### Alternative – build a JAR first

```bash
./mvnw package -DskipTests
java -jar target/ws-poc-0.0.1-SNAPSHOT.jar
```

---

## 🌐 Using the Chat UI

1. Open **http://localhost:8080** in your browser.
2. Enter a **display name** and click **Connect**.
3. The status indicator turns green when the WebSocket is established.

### Broadcast messages

- Select the **📢 Broadcast** tab.
- Type a message and press **Enter** or **Send**.
- All connected clients receive it via `/topic/messages`.

### Private (DM) messages

- Select the **🔒 Private DM** tab.
- Enter the **recipient's exact username** in the "To" field.
- Send – only the recipient receives the message on `/user/queue/private`.

---

## 🧪 Testing

### Option A – Integration tests (automated)

```bash
./mvnw test
```

Runs **4 end-to-end integration tests** that spin up the full Spring Boot application on a random port and exercise the real WebSocket stack:

| Test | What it verifies |
|---|---|
| `broadcastMessage_shouldBeReceivedByAllSubscribers` | `/app/chat.send` → `/topic/messages` round-trip |
| `joinMessage_shouldBroadcastJoinNotification` | `/app/chat.join` emits a `JOIN` notification |
| `privateMessage_shouldBeDeliveredToTargetUser` | `/app/chat.private` → `/user/queue/private` |
| `broadcast_shouldFanOutToAllSubscribers` | Two separate sessions both receive the same message |

### Option B – Manual browser test

Open **two separate browser windows / tabs** at `http://localhost:8080`:

1. **Window 1** – connect as `Alice`
2. **Window 2** – connect as `Bob`
3. Alice sends a broadcast → Bob sees it, Bob sends → Alice sees it.
4. Switch to Private DM, Alice enters `Bob` as recipient and sends → only Bob gets it.

### Option C – Postman (WebSocket collection)

1. Open Postman → **New → WebSocket Request**
2. URL: `ws://localhost:8080/ws/websocket`
   *(Note: the `/websocket` suffix is the SockJS raw WebSocket transport path)*
3. After connecting, send a raw STOMP CONNECT frame:

```
CONNECT
accept-version:1.2
heart-beat:0,0

\x00
```

Then subscribe and send:

```
SUBSCRIBE
id:sub-0
destination:/topic/messages

\x00
```

```
SEND
destination:/app/chat.send
content-type:application/json

{"from":"PostmanUser","content":"Hello from Postman!","type":"CHAT"}
\x00
```

### Option D – wscat (CLI)

```bash
npm install -g wscat

# Connect via the raw WebSocket transport
wscat -c ws://localhost:8080/ws/websocket
```

Then paste each STOMP frame and terminate with a null byte (Ctrl+@ or `\x00`):

```
CONNECT
accept-version:1.2

```

---

## 📡 WebSocket Endpoints Reference

| Endpoint | Type | Description |
|---|---|---|
| `/ws` | STOMP endpoint (SockJS) | Client connection point |
| `/app/chat.send` | Send destination | Broadcast a CHAT message |
| `/app/chat.join` | Send destination | Announce user join |
| `/app/chat.private` | Send destination | Send a private DM |
| `/topic/messages` | Subscribe | Receive all broadcast messages |
| `/user/queue/private` | Subscribe | Receive private messages for current user |

---

## 📨 Message Schema

```json
{
  "from":      "Alice",
  "to":        "Bob",
  "content":   "Hello!",
  "type":      "CHAT | JOIN | LEAVE | PRIVATE",
  "timestamp": "2026-09-28T16:37:00.123456Z"
}
```

- `to` is `null` for broadcast messages.
- `timestamp` is set server-side (ISO-8601 UTC).

---

## ⚙️ Configuration

All settings live in `src/main/resources/application.yml`:

```yaml
server:
  port: 8080        # change port here

spring:
  thymeleaf:
    cache: false    # set to true in production
```

---

## 🏭 Production Considerations

| Concern | Recommendation |
|---|---|
| **Allowed origins** | Replace `allowedOriginPatterns("*")` with your domain |
| **Message broker** | Swap the in-memory broker for **RabbitMQ / ActiveMQ** via `enableStompBrokerRelay` |
| **Authentication** | Add Spring Security; use `Principal` in `@MessageMapping` for user identity |
| **Scaling** | With an external broker every node can subscribe → horizontal scaling |
| **TLS** | Terminate at load balancer or configure HTTPS in `application.yml` |

---

## 📁 Project Structure

```
ws-poc/
├── pom.xml
├── mvnw / mvnw.cmd            ← Maven wrapper (no Maven install needed)
├── .mvn/wrapper/
│   └── maven-wrapper.properties
└── src/
    ├── main/
    │   ├── java/com/example/wspoc/
    │   │   ├── WsPocApplication.java
    │   │   ├── config/
    │   │   │   ├── WebSocketConfig.java   ← STOMP broker setup
    │   │   │   └── CorsConfig.java
    │   │   ├── controller/
    │   │   │   ├── ChatController.java    ← @MessageMapping handlers
    │   │   │   ├── WebSocketEventListener.java
    │   │   │   └── PageController.java
    │   │   └── model/
    │   │       └── ChatMessage.java       ← Java record
    │   └── resources/
    │       ├── application.yml
    │       └── templates/
    │           └── index.html             ← Browser chat client
    └── test/
        └── java/com/example/wspoc/
            └── ChatIntegrationTest.java   ← 4 integration tests
```
