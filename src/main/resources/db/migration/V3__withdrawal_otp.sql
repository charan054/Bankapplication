-- Emailed confirmation codes for large self-service withdrawals (WithdrawalOtpService). One live code per account.
CREATE TABLE IF NOT EXISTS `withdrawal_otp` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `phno` bigint NOT NULL,
  `otp_hash` varchar(64) NOT NULL,
  `amount` decimal(19,2) NOT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `expires_at` datetime(6) DEFAULT NULL,
  `attempts` int NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_withdrawal_otp_phno` (`phno`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
