-- Defaults carried over from v1 so a fresh install is usable. Synthetic data only.
-- The first-run wizard (later) sets the real restaurant name and the owner PIN.

INSERT INTO settings (id, restaurant_name, low_stock_default, sound_muted, failed_pin_attempts)
VALUES (1, 'My Restaurant', 5, FALSE, 0);

INSERT INTO menu_item (name, price, stock, low_stock_threshold, color, active, created_at) VALUES
    ('Meat Kofta',    2500, 100, 5, '#e74c3c', TRUE, CURRENT_TIMESTAMP),
    ('Chicken Kofta', 2000, 100, 5, '#27ae60', TRUE, CURRENT_TIMESTAMP),
    ('Fateera',       1500, 100, 5, '#2980b9', TRUE, CURRENT_TIMESTAMP);

INSERT INTO addon (name, active) VALUES
    ('Onion', TRUE), ('Tomato', TRUE), ('Mayo', TRUE), ('Ketchup', TRUE), ('Spicy', TRUE),
    ('Chocolate', TRUE), ('Lutz', TRUE), ('D', TRUE), ('TAKE WAY', TRUE), ('No Onion', TRUE);
