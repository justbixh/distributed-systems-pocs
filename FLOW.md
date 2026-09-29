# Request Flow Guide — Spring Boot WebSocket POC

This document traces every type of request from the browser through the server
so you can see exactly which files are involved and why.

---

## 1. App Startup

```
main() → WsPocApplication.java
```

| File | What happens |
|------|-------------|
| `WsPocApplication.java` | `@SpringBootApplication` triggers Spring Boot auto-configuration. Scans all `@Configuration`, `@Controller`, `@Component` classes. |
| `WebSocketConfig.java` | Registers the STOMP endpoint `/ws` (with SockJS fallback). Configures an in-memory message broker with destinations `/topic`, `/queue`, and maps `/app` → controllers. |
| `CorsConfig.java` | Opens CORS for all origins so the browser client can connect freely in dev. |
| `application.yml` | Sets port **8080**, disables Thymeleaf template cache in dev, configures log levels. |

---

## 2. Browser Opens the Page — HTTP GET `/`

```
Browser GET / → PageController → Thymeleaf → index.html
```

```
Browser
  │
  │  HTTP GET http://localhost:8080/
  ▼
PageController.java  (@GetMapping("/"))
  │  return "index"
  ▼
Thymeleaf resolves → src/main/resources/templates/index.html
  │
  ▼
Browser renders the login card (username input + Connect button)
```

**Files touched:**

| File | Role |
|------|------|
| `PageController.java` | The only REST controller. `return "index"` tells Thymeleaf to render `index.html`. |
| `index.html` | The entire frontend: HTML, CSS, and JavaScript in one file. Loads SockJS and STOMP.js from CDN. |

---

## 3. User Clicks "Connect" — WebSocket Handshake

```
Browser → SockJS → /ws (HTTP Upgrade) → WebSocketConfig
```

```
User types username "Alice", clicks ⚡ Connect
  │
  ▼
index.html  connect() function
  │  new SockJS('/ws')              ← falls back to long-polling if WS blocked
  │  new StompJs.Client(...)
  │  stompClient.activate()
  ▼
WebSocketConfig.java
  │  endpoint registered at /ws
  │  withSockJS() handles the HTTP→WebSocket upgrade negotiation
  ▼
Session is established — STOMP CONNECTED frame sent back
  │
  ▼
WebSocketEventListener.java  handleWebSocketConnectListener()
  │  Logs: "New WebSocket session connected: <sessionId>"
  ▼
index.html  onConnect callback fires
  │  stompClient.subscribe('/topic/messages', onBroadcastMessage)
  │  stompClient.subscribe('/user/queue/private', onPrivateMessage)
```

**Key concept:** `/ws` is the single WebSocket endpoint. Once connected, everything
else is STOMP *frames* sent over that same connection — no more HTTP.

---

## 4. User Sends a Join Notification — `/app/chat.join`

Immediately after connecting the browser sends a JOIN frame:

```
index.html
  │  stompClient.publish({ destination: '/app/chat.join', body: {from:"Alice", type:"JOIN"} })
  ▼
Spring STOMP router
  │  sees prefix /app → route to @MessageMapping controller
  ▼
ChatController.java  handleJoin()
  │  Reads username from ChatMessage.from()
  │  Stores username in STOMP session attributes (used later on disconnect)
  │  return ChatMessage.of("Alice", null, "Alice joined the chat!", JOIN)
  │  @SendTo("/topic/messages") auto-publishes the return value
  ▼
In-memory broker (configured in WebSocketConfig)
  │  fans out to every subscriber of /topic/messages
  ▼
ALL browsers' onBroadcastMessage() callback fires
  │  renderMessage() — renders a system bubble: "Alice joined the chat!"
```

**Files touched:** `index.html` → `ChatController.java` → `ChatMessage.java` (the model record) → broker → all browsers

---

## 5. User Sends a Broadcast Message — `/app/chat.send`

```
User types "Hello everyone!", clicks Send
  │
  ▼
index.html  sendMessage()
  │  currentTab === 'broadcast'
  │  stompClient.publish({ destination: '/app/chat.send', body: {from:"Alice", content:"Hello everyone!", type:"CHAT"} })
  ▼
Spring STOMP router → ChatController.java  handleBroadcast()
  │  Logs the message
  │  return ChatMessage.of("Alice", null, "Hello everyone!", CHAT)
  │    ↑ ChatMessage.of() stamps server-side Instant.now() as the timestamp
  │  @SendTo("/topic/messages")
  ▼
In-memory broker fans out to all /topic/messages subscribers
  ▼
Every connected browser receives the frame → onBroadcastMessage() → renderMessage()
  │  isOwn = (msg.from === myName)  → Alice sees it right-aligned (purple bubble)
  │  others see it left-aligned (grey bubble)
```

**Files touched:**

| File | What it does |
|------|-------------|
| `index.html` | `sendMessage()` publishes the STOMP frame |
| `ChatController.java` | `handleBroadcast()` receives, logs, stamps timestamp, returns |
| `ChatMessage.java` | The data record — `from`, `to`, `content`, `type`, `timestamp` |
| `WebSocketConfig.java` | The in-memory broker configured here does the fan-out |

---

## 6. User Sends a Private Message — `/app/chat.private`

```
User switches to 🔒 Private DM tab, enters recipient "Bob", types "Hey Bob!"
  │
  ▼
index.html  sendMessage()
  │  currentTab === 'private'
  │  stompClient.publish({ destination: '/app/chat.private', body: {from:"Alice", to:"Bob", content:"Hey Bob!", type:"PRIVATE"} })
  ▼
Spring STOMP router → ChatController.java  handlePrivate()
  │  sender = "Alice",  recipient = "Bob"
  │
  ├─ messagingTemplate.convertAndSendToUser("Bob",   "/queue/private", msg)
  │     → delivers to /user/Bob/queue/private   (only Bob's session)
  │
  └─ messagingTemplate.convertAndSendToUser("Alice", "/queue/private", echo)
        → delivers to /user/Alice/queue/private  (echo so Alice sees her own DM)
  ▼
Bob's browser   → onPrivateMessage() → renderMessage(msg, isPrivate=true)
                  (purple border bubble + 🔒 icon)
                  if Bob is on Broadcast tab → showToast("💬 New private message from Alice")

Alice's browser → onPrivateMessage() → renderMessage(echo, isPrivate=true)
                  content shown as "[to Bob] Hey Bob!"
```

**Key:** `SimpMessagingTemplate.convertAndSendToUser()` combines the `/user` prefix
(set in `WebSocketConfig`) with the username to build a session-specific destination.
Only that user's subscription receives it.

---

## 7. User Disconnects — Session Cleanup

```
User clicks Disconnect (or closes tab)
  │
  ▼
index.html  disconnect()
  │  stompClient.deactivate()
  ▼
Spring fires SessionDisconnectEvent
  │
  ▼
WebSocketEventListener.java  handleWebSocketDisconnectListener()
  │  Reads "username" from STOMP session attributes (stored during chat.join)
  │  messagingTemplate.convertAndSend("/topic/messages",
  │      ChatMessage.of("Alice", null, "Alice left the chat.", LEAVE))
  ▼
All remaining browsers receive LEAVE frame → system bubble rendered
```

---

## Full Picture — File Responsibility Map

```
src/main/
├── java/com/example/wspoc/
│   │
│   ├── WsPocApplication.java            ← Entry point. Boots everything.
│   │
│   ├── config/
│   │   ├── WebSocketConfig.java          ← Defines /ws endpoint, broker topics,
│   │   │                                    /app prefix, /user prefix
│   │   └── CorsConfig.java               ← Allows browser cross-origin access
│   │
│   ├── controller/
│   │   ├── PageController.java           ← Serves index.html on GET /
│   │   ├── ChatController.java           ← Handles /app/chat.send, .join, .private
│   │   └── WebSocketEventListener.java   ← Handles connect/disconnect lifecycle events
│   │
│   └── model/
│       └── ChatMessage.java              ← The data shape: from, to, content, type, timestamp
│
└── resources/
    ├── application.yml                   ← Port, logging, Thymeleaf config
    └── templates/
        └── index.html                    ← The entire browser client (HTML + CSS + JS)
```

---

## STOMP Destination Cheat-Sheet

| Who sends | Destination | Who receives |
|-----------|-------------|--------------|
| Browser | `/app/chat.join` | → `ChatController.handleJoin()` |
| Browser | `/app/chat.send` | → `ChatController.handleBroadcast()` |
| Browser | `/app/chat.private` | → `ChatController.handlePrivate()` |
| Server (`@SendTo`) | `/topic/messages` | → **all** subscribers |
| Server (`convertAndSendToUser`) | `/user/{name}/queue/private` | → **one** specific user |
| Browser subscribes to | `/topic/messages` | broadcast messages |
| Browser subscribes to | `/user/queue/private` | private messages for me |

---

## Key Concepts in Plain English

- **SockJS** — a JS library that tries a real WebSocket first; falls back to HTTP
  long-polling if the network blocks WebSockets. The server side enables this with
  `.withSockJS()` in `WebSocketConfig`.
- **STOMP** — a simple text protocol layered on top of WebSocket. Think of it like
  HTTP but for real-time. Messages have a `destination` header (like a URL) and a `body`.
- **`/app` prefix** — tells the broker "route this to a Java `@MessageMapping` method".
  Without this prefix messages go straight to the broker and skip your code.
- **`/topic`** — broadcast channel (pub/sub). One message → every subscriber gets it.
- **`/queue`** — point-to-point channel. One message → one specific session.
- **`/user`** — Spring's magic prefix. Turns `/user/queue/private` into
  `/user/Alice/queue/private` using the logged-in username, so only Alice receives it.
- **`@SendTo`** — shortcut on a controller method: whatever you `return` gets
  automatically published to that destination.
- **`SimpMessagingTemplate`** — injected bean used when you need to push a message
  from code (not a return value), e.g. to a specific user or from an event listener.
- **`ChatMessage` record** — Java 16+ record. Immutable value object. The `of()`
  factory stamps the server timestamp automatically.
