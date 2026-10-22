-- Self-signups waiting for their emailed confirmation code (SignupService). The account is created only once the code is entered.
CREATE TABLE IF NOT EXISTS `pending_signup` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `phno` bigint NOT NULL,
  `otp_hash` varchar(64) NOT NULL,
  `first_name` varchar(255) DEFAULT NULL,
  `last_name` varchar(255) DEFAULT NULL,
  `aadhar_number` bigint NOT NULL,
  `email` varchar(255) DEFAULT NULL,
  `pin_hash` varchar(255) DEFAULT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `expires_at` datetime(6) DEFAULT NULL,
  `attempts` int NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pending_signup_phno` (`phno`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
