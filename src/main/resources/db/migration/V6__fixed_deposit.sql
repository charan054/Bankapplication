-- Money locked for a fixed term at a fixed rate (FixedDepositService).
CREATE TABLE IF NOT EXISTS `fixed_deposit` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `phno` bigint NOT NULL,
  `principal` decimal(19,2) DEFAULT NULL,
  `rate_percent` decimal(5,2) DEFAULT NULL,
  `term_months` int NOT NULL,
  `opened_at` datetime(6) DEFAULT NULL,
  `matures_at` datetime(6) DEFAULT NULL,
  `status` varchar(10) NOT NULL,
  `payout` decimal(19,2) DEFAULT NULL,
  `closed_at` datetime(6) DEFAULT NULL,
  `version` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_fixed_deposit_phno` (`phno`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
