CREATE TABLE orders (
  id   VARCHAR(36) PRIMARY KEY,
  item VARCHAR(100) NOT NULL
);

-- the outbox: events waiting to be published, written in the SAME transaction as the order
CREATE TABLE outbox (
  id      BIGINT AUTO_INCREMENT PRIMARY KEY,
  topic   VARCHAR(100) NOT NULL,
  msg_key VARCHAR(100) NOT NULL,
  payload VARCHAR(1000) NOT NULL,
  sent    BOOLEAN NOT NULL DEFAULT FALSE
);
