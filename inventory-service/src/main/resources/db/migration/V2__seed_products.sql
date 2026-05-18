-- Demo product catalogue. Stable UUIDs so REST callers can refer to them directly.
INSERT INTO products (id, sku, name, stock_available, stock_reserved) VALUES
    ('11111111-1111-1111-1111-111111111111', 'SKU-001', 'Notebook',  100, 0),
    ('22222222-2222-2222-2222-222222222222', 'SKU-002', 'Mechanical Keyboard',  20, 0),
    ('33333333-3333-3333-3333-333333333333', 'SKU-003', 'Wireless Mouse',  50, 0),
    ('44444444-4444-4444-4444-444444444444', 'SKU-004', 'Monitor 27"',   5, 0);
