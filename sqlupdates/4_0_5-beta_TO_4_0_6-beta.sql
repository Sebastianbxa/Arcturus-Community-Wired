CREATE TABLE IF NOT EXISTS `wired_logs` (
    `id` int(11) NOT NULL AUTO_INCREMENT,
    `room_id` int(11) NOT NULL,
    `created_at` bigint(20) NOT NULL,
    `source` varchar(32) NOT NULL DEFAULT 'WIRED',
    `category` varchar(16) NOT NULL DEFAULT 'INFO',
    `message` varchar(255) NOT NULL DEFAULT '',
    PRIMARY KEY (`id`),
    KEY `wired_logs_room_newest` (`room_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO `emulator_settings` (`key`, `value`) VALUES
    ('wired.usage.window.ms', '1000'),
    ('wired.usage.stack_baseline.interval.ms', '1000'),
    ('wired.delayed.events.max', '500'),
    ('wired.executor.overload.ms', '250'),
    ('wired.signal.dispatch.batch.size', '25'),
    ('wired.signal.maxPayloadItems', '100'),
    ('wired.engine.maxStepsPerExecution', '1000'),
    ('wired.engine.maxEventsPerWindow', '1000'),
    ('wired.movement.item_interval.ms', '45'),
    ('wired.movement.persist.delay.ms', '5000'),
    ('hotel.wired.message.max_length', '200'),
    ('hotel.wired.log.max_length', '215')
ON DUPLICATE KEY UPDATE `value` = `value`;

UPDATE `emulator_settings`
SET `value` = '1000'
WHERE `key` = 'wired.usage.window.ms' AND `value` = '500';

INSERT INTO `emulator_texts` (`key`, `value`) VALUES
    ('wiredchests.view_logs', 'View logs'),
    ('wiredfurni.params.write_to_logs.log_level', 'Log level'),
    ('wiredfurni.params.write_to_logs.log_message', 'Log message'),
    ('wiredfurni.params.log_info', 'INFO'),
    ('wiredfurni.params.log_warn', 'WARN'),
    ('wiredfurni.params.log_error', 'ERROR'),
    ('wiredfurni.params.log_debug', 'DEBUG')
ON DUPLICATE KEY UPDATE `value` = `value`;

ALTER TABLE `wired_variables`
    ADD COLUMN IF NOT EXISTS `revision` bigint(20) NOT NULL DEFAULT 0 AFTER `updated_at`,
    ADD INDEX IF NOT EXISTS `idx_wired_variables_room_owner` (`room_id`, `owner_type`, `owner_id`);

DELETE FROM `wired_variables`
WHERE `owner_type` = 2 AND `owner_id` < 0;

INSERT INTO `emulator_settings` (`key`, `value`) VALUES
    ('hotel.room.variable.definition.max', '10000'),
    ('hotel.room.variable.total.max', '50000'),
    ('hotel.room.variable.flush.interval.ms', '2000'),
    ('hotel.room.variable.flush.batch.size', '500'),
    ('hotel.room.variable.flush.retry.max.ms', '30000')
ON DUPLICATE KEY UPDATE `value` = `value`;
