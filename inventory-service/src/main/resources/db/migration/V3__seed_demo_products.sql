-- Extra demo products to exercise specific scenarios.
-- SKU-005: very low stock to trigger insufficient-stock failures fast.
-- SKU-006: high stock to use as the "happy path" workhorse in load tests.
-- SKU-007: medium stock for race / contention tests.
INSERT INTO products (id, sku, name, stock_available, stock_reserved) VALUES
    ('55555555-5555-5555-5555-555555555555', 'SKU-005', 'Flash Sale GPU',          10,    0),
    ('66666666-6666-6666-6666-666666666666', 'SKU-006', 'Coffee Mug (bulk)',    10000,    0),
    ('77777777-7777-7777-7777-777777777777', 'SKU-007', 'Limited Hoodie',         200,    0);
