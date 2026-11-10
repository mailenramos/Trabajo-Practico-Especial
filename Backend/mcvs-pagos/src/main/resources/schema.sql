CREATE UNIQUE INDEX IF NOT EXISTS uk_payment_approved_purchase
ON payments (purchase_id)
WHERE status = 'APROBADO';
