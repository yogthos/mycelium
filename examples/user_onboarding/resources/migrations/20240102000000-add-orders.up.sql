CREATE TABLE orders (
  id         TEXT PRIMARY KEY,
  user_id    TEXT REFERENCES users(id),
  item       TEXT NOT NULL,
  amount     REAL NOT NULL,
  created_at TEXT DEFAULT (datetime('now'))
);
--;;
INSERT INTO orders VALUES ('ord_001','alice','Widget Pro',29.99,'2024-01-15');
--;;
INSERT INTO orders VALUES ('ord_002','alice','Gadget Max',49.99,'2024-01-20');
--;;
INSERT INTO orders VALUES ('ord_003','bob','Widget Pro',29.99,'2024-01-18');
