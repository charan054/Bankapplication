-- Baseline of the Bankapplication schema as the JPA entities define it on 2026-10-08.
-- Every statement is IF NOT EXISTS, so it is a no-op on a database that Hibernate (ddl-auto=update)
-- already built, and builds the whole schema on a brand-new one. All services share one MySQL
-- schema, so each keeps its own history table (see spring.flyway.table).

CREATE TABLE IF NOT EXISTS `bank` (
  `balance` decimal(19,2) DEFAULT NULL,
  `failed_login_attempts` int NOT NULL,
  `user_id` int NOT NULL AUTO_INCREMENT,
  `aadhar_number` bigint DEFAULT NULL,
  `acno` bigint DEFAULT NULL,
  `locked_until` datetime(6) DEFAULT NULL,
  `phno` bigint DEFAULT NULL,
  `version` bigint NOT NULL,
  `email` varchar(255) DEFAULT NULL,
  `first_name` varchar(255) DEFAULT NULL,
  `last_name` varchar(255) DEFAULT NULL,
  `pin_hash` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`user_id`),
  UNIQUE KEY `UKaas8j5ghbq302a9dl8ecly2g7` (`aadhar_number`),
  UNIQUE KEY `UKfcm9ijv5eykw7tpki99172had` (`acno`),
  UNIQUE KEY `UK9lg94yx4h7vt7ual4midkf20f` (`phno`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS `bank_session` (
  `created_at` datetime(6) DEFAULT NULL,
  `expires_at` datetime(6) DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `phno` bigint NOT NULL,
  `token_hash` varchar(64) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK9uc5ayvw2i5pd44g2397u3hoh` (`token_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS `bank_transactions` (
  `amount` decimal(19,2) DEFAULT NULL,
  `balance` decimal(19,2) DEFAULT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `phno` bigint NOT NULL,
  `transaction_id` bigint DEFAULT NULL,
  `user_id` bigint DEFAULT NULL,
  `action` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKpshe4tsswb3ssp0uav9d8govn` (`transaction_id`),
  KEY `idx_banktransactions_phno` (`phno`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS `pin_reset_otp` (
  `attempts` int NOT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `expires_at` datetime(6) DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `phno` bigint NOT NULL,
  `otp_hash` varchar(64) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKsi8deg51vglkd64psgesuplib` (`otp_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS `transfer` (
  `amount` decimal(19,2) DEFAULT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `credit_transaction_id` bigint NOT NULL,
  `debit_transaction_id` bigint NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `payer_phno` bigint NOT NULL,
  `receiver_phno` bigint NOT NULL,
  `idempotency_key` varchar(255) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_transfer_payer_idempotency_key` (`payer_phno`,`idempotency_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
