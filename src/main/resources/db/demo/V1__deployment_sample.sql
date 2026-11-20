CREATE TABLE deployment_sample (
    id BIGINT PRIMARY KEY,
    label VARCHAR(100) NOT NULL,
    amount NUMERIC(12, 2) NOT NULL
);

INSERT INTO deployment_sample (id, label, amount) VALUES
    (1, 'Demo order A', 1250.00),
    (2, 'Demo order B', 2490.50),
    (3, 'Demo order C', 750.00);
