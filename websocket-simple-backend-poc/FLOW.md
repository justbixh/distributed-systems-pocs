# Backend message flow

Follow one message from connection to broadcast:

```text
Browser tab                 Spring Boot
    |                            |
    | HTTP request: GET /ws      |
    |--------------------------->| WebSocketConfig registers /ws
    | HTTP 101 Switching         |
    | Protocols                  |
    |<==========================>| Persistent WebSocket connection
    |                            |
    | text: "Alice: Hello"       |
    |--------------------------->| ChatWebSocketHandler.handleTextMessage
    |                            | loops over connected WebSocketSessions
    |<---------------------------| sends a text frame to each open session
```

The `GET /ws` request is the initial handshake. A successful WebSocket handshake
returns HTTP `101 Switching Protocols`; after this, messages are WebSocket
frames, not separate REST requests.

## Server lifecycle

1. `WebSocketConfig.registerWebSocketHandlers` maps `/ws` to
   `ChatWebSocketHandler`.
2. `afterConnectionEstablished` adds a newly connected session to the
   thread-safe in-memory set.
3. `handleTextMessage` receives the sender session and a `TextMessage`. It sends
   that payload to every currently open session, including the sender.
4. `afterConnectionClosed` removes the session from the set.

There is no `@RestController` or `@MessageMapping`: a WebSocket message is
handled by the `TextWebSocketHandler` callback, rather than by a new HTTP route
for each message.

## Browser client

`src/main/resources/static/index.html` uses the browser's built-in `WebSocket`
API. The page displays connection state, sends text from its form, and renders
incoming text with `textContent`. It is a tiny testing aid; all connection
management and broadcasting are implemented by the Java backend.

## Boundaries of this demo

The connection set is local to this one JVM. The message is not persisted,
filtered, authenticated, or routed to a particular user. The page and WebSocket
endpoint are served from the same origin, so this demo does not configure
cross-origin access.
