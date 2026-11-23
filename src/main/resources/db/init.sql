CREATE DATABASE IF NOT EXISTS enterprise_copilot
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE enterprise_copilot;

CREATE TABLE IF NOT EXISTS biz_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    order_no VARCHAR(32) NOT NULL UNIQUE COMMENT '订单编号，如 ORD-2026001',
    user_id VARCHAR(32) NOT NULL COMMENT '用户ID',
    product_name VARCHAR(128) NOT NULL COMMENT '商品或服务名称',
    amount DECIMAL(10, 2) NOT NULL COMMENT '实付金额',
    status VARCHAR(20) NOT NULL COMMENT 'PENDING_SHIP, SHIPPED, COMPLETED, REFUNDED',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '下单时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB COMMENT='业务订单表';

CREATE TABLE IF NOT EXISTS service_ticket (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    ticket_no VARCHAR(32) NOT NULL UNIQUE COMMENT '工单编号，如 TKT-2026001',
    order_no VARCHAR(32) NOT NULL COMMENT '关联订单编号',
    user_id VARCHAR(32) NOT NULL COMMENT '申请人用户ID',
    ticket_type VARCHAR(20) NOT NULL COMMENT 'REFUND, EXCHANGE, COMPLAINT',
    reason VARCHAR(255) NOT NULL COMMENT '申请原因',
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN, APPROVED, REJECTED',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_order_no (order_no)
) ENGINE=InnoDB COMMENT='售后工单表';

CREATE TABLE IF NOT EXISTS chat_audit_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    session_id VARCHAR(64) NOT NULL COMMENT '会话ID',
    user_id VARCHAR(32) NOT NULL COMMENT '用户ID',
    user_message TEXT NOT NULL COMMENT '用户输入内容',
    assistant_reply TEXT COMMENT 'Agent最终回复',
    tools_invoked VARCHAR(255) COMMENT '本次触发的工具名称列表',
    latency_ms BIGINT COMMENT '端到端响应耗时(毫秒)',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间',
    INDEX idx_session_id (session_id)
) ENGINE=InnoDB COMMENT='Agent对话审计与观测日志表';

INSERT INTO biz_order (order_no, user_id, product_name, amount, status) VALUES
    ('ORD-2026001', 'U1001', '企业级云服务器 4核8G 年付套餐', 1299.00, 'PENDING_SHIP'),
    ('ORD-2026002', 'U1001', '智能硬件开发套件 Pro 版', 599.00, 'SHIPPED'),
    ('ORD-2026003', 'U1002', '高速企业专线宽带月付包', 300.00, 'COMPLETED')
ON DUPLICATE KEY UPDATE order_no = biz_order.order_no;
