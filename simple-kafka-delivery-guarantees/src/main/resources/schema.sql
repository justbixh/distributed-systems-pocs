-- the business data
CREATE TABLE IF NOT EXISTS orders (
  id   VARCHAR(36)  PRIMARY KEY,
  item VARCHAR(100) NOT NULL
);

-- OUTBOX: "messages to send", written in the same transaction as the order, so both exist or neither does
CREATE TABLE IF NOT EXISTS outbox (
  id        BIGINT AUTO_INCREMENT PRIMARY KEY,
  topic     VARCHAR(100)  NOT NULL,
  msg_key   VARCHAR(100),
  payload   VARCHAR(1000) NOT NULL,
  published BOOLEAN       NOT NULL DEFAULT FALSE
);

-- INBOX: ids of messages already handled. The PRIMARY KEY is what makes a second delivery fail.
CREATE TABLE IF NOT EXISTS processed_messages (
  message_id VARCHAR(100) PRIMARY KEY
);

-- the side effect of handling a message (stands in for "ship the order", "charge the card", ...)
CREATE TABLE IF NOT EXISTS fulfilled (
  id      BIGINT AUTO_INCREMENT PRIMARY KEY,
  payload VARCHAR(1000) NOT NULL
);
