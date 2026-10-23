-- Customer-chosen daily cap on money leaving an account, and a raise that is waiting out its delay (DailyLimitService).
ALTER TABLE `bank` ADD COLUMN `daily_limit` decimal(19,2) DEFAULT NULL;
ALTER TABLE `bank` ADD COLUMN `pending_daily_limit` decimal(19,2) DEFAULT NULL;
ALTER TABLE `bank` ADD COLUMN `pending_limit_effective_at` datetime(6) DEFAULT NULL;
