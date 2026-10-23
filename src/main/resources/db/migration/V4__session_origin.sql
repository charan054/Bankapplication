-- Where each login came from, for the customer's "Active logins" list.
ALTER TABLE `bank_session` ADD COLUMN `user_agent` varchar(200) DEFAULT NULL;
ALTER TABLE `bank_session` ADD COLUMN `remote_addr` varchar(45) DEFAULT NULL;
