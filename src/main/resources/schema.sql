-- ============================================
-- DTBD AI Service 数据库表结构
-- 数据库: dtbd_ai
-- ============================================

CREATE TABLE IF NOT EXISTS `c_ai_document` (
    `id`           VARCHAR(50)  NOT NULL COMMENT '主键ID',
    `file_name`    VARCHAR(200) DEFAULT NULL COMMENT '文件名',
    `file_type`    VARCHAR(20)  DEFAULT NULL COMMENT '文件类型: docx/pdf/xlsx',
    `chunk_count`  INT          DEFAULT 0 COMMENT '分块数量',
    `status`       INT          DEFAULT 0 COMMENT '状态: 0=生效, 1=已弃用, 2=解析中, 3=解析失败',
    `fail_reason`  VARCHAR(500) DEFAULT NULL COMMENT '解析失败原因(status=3)',
    `file_size`    BIGINT       DEFAULT 0 COMMENT '文件大小(字节)',
    `description`  VARCHAR(500) DEFAULT NULL COMMENT '文档描述',
    `create_time`  DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`      INT          DEFAULT 0 COMMENT '逻辑删除: 0=未删除, 1=已删除',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI文档表';

-- 2026-08-04: 知识片段新增 images 字段（关联图片URL JSON数组）
CREATE TABLE IF NOT EXISTS `c_ai_knowledge` (
    `id`           VARCHAR(50)  NOT NULL COMMENT '主键ID',
    `doc_id`       VARCHAR(50)  DEFAULT NULL COMMENT '所属文档ID',
    `title`        VARCHAR(200) DEFAULT NULL COMMENT '片段标题',
    `content`      TEXT         DEFAULT NULL COMMENT '片段正文',
    `images`       VARCHAR(2000) DEFAULT NULL COMMENT '关联图片URL(JSON数组)',
    `chunk_index`  INT          DEFAULT 0 COMMENT '片段序号',
    `vector_id`    VARCHAR(50)  DEFAULT NULL COMMENT 'Redis向量库中的文档ID',
    `create_time`  DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `deleted`      INT          DEFAULT 0 COMMENT '逻辑删除: 0=未删除, 1=已删除',
    PRIMARY KEY (`id`),
    KEY `idx_doc_id` (`doc_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI知识片段表';

-- ============================================
-- 2026-08-10: 会话持久化 & 历史回放
-- ============================================

CREATE TABLE IF NOT EXISTS `c_ai_session` (
    `id`            VARCHAR(50)  NOT NULL COMMENT '主键ID (UUID无横线)',
    `title`         VARCHAR(200) DEFAULT NULL COMMENT '会话标题 (取自首条用户问题)',
    `message_count` INT          DEFAULT 0 COMMENT '消息条数',
    `create_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`       INT          DEFAULT 0 COMMENT '逻辑删除: 0=未删除, 1=已删除',
    PRIMARY KEY (`id`),
    KEY `idx_deleted_update` (`deleted`, `update_time` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI会话表';

CREATE TABLE IF NOT EXISTS `c_ai_message` (
    `id`          VARCHAR(50)  NOT NULL COMMENT '主键ID (UUID无横线)',
    `session_id`  VARCHAR(50)  NOT NULL COMMENT '所属会话ID',
    `role`        VARCHAR(20)  NOT NULL COMMENT '角色: user / assistant',
    `content`     TEXT         NOT NULL COMMENT '消息内容',
    `images`      VARCHAR(2000) DEFAULT NULL COMMENT '关联图片URL (JSON数组字符串)',
    `sources`     TEXT         DEFAULT NULL COMMENT '引用来源 (JSON数组字符串)',
    `sequence`    INT          NOT NULL DEFAULT 0 COMMENT '消息序号 (会话内递增)',
    `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `deleted`     INT          DEFAULT 0 COMMENT '逻辑删除: 0=未删除, 1=已删除',
    PRIMARY KEY (`id`),
    KEY `idx_session_id` (`session_id`, `sequence`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI消息表';

-- 2026-08-11: 存量库需手动执行以下 ALTER（消息表新增引用来源列）
-- ALTER TABLE `c_ai_message` ADD COLUMN `sources` TEXT DEFAULT NULL COMMENT '引用来源 (JSON数组字符串)' AFTER `images`;
-- 2026-08-11: 文档表新增解析失败原因列
-- ALTER TABLE `c_ai_document` ADD COLUMN `fail_reason` VARCHAR(500) DEFAULT NULL COMMENT '解析失败原因(status=3)' AFTER `status`;
-- 2026-08-13: 存量库需手动执行以下 ALTER（问答日志新增改写问题列）
-- ALTER TABLE `c_ai_qa_log` ADD COLUMN `rewritten_query` TEXT DEFAULT NULL COMMENT '改写后的检索用问题' AFTER `question`;

-- ============================================
-- 2026-08-11: 问答数据闭环（日志 + 反馈）
-- ============================================

CREATE TABLE IF NOT EXISTS `c_ai_qa_log` (
    `id`              VARCHAR(50)  NOT NULL COMMENT '主键ID',
    `session_id`      VARCHAR(50)  DEFAULT NULL COMMENT '会话ID',
    `question`        TEXT         COMMENT '用户问题',
    `rewritten_query` TEXT         DEFAULT NULL COMMENT '改写后的检索用问题',
    `answer_summary`  VARCHAR(1000) DEFAULT NULL COMMENT '回答摘要(前500字)',
    `hit_doc_ids`     VARCHAR(1000) DEFAULT NULL COMMENT '命中文档ID列表(逗号分隔)',
    `has_citation`    INT          DEFAULT 0 COMMENT '是否有引用标注',
    `elapsed_ms`      INT          DEFAULT 0 COMMENT '回答耗时(ms)',
    `created_at`      DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI问答日志表';

CREATE TABLE IF NOT EXISTS `c_ai_qa_feedback` (
    `id`             VARCHAR(50)  NOT NULL COMMENT '主键ID',
    `message_id`     VARCHAR(50)  NOT NULL COMMENT '关联消息ID',
    `rating`         INT          DEFAULT 0 COMMENT '1=有帮助 0=没帮助',
    `feedback_text`  VARCHAR(500) DEFAULT NULL COMMENT '反馈文本',
    `created_at`     DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_message` (`message_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI问答反馈表';
CREATE TABLE IF NOT EXISTS `c_ai_config` (
    `config_key`    VARCHAR(64)   NOT NULL COMMENT '配置键（chat.model/chat.temperature/vision.model/...）',
    `config_value`  TEXT          NOT NULL COMMENT '配置值（TEXT 以容纳长 system prompt）',
    `remark`        VARCHAR(255)  DEFAULT NULL COMMENT '说明',
    `update_time`   DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`config_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI模型配置表';

-- ============================================
-- 2026-08-13: 产品功能补强（会话置顶收藏 / 文档分类 / 文档版本）
-- ============================================

CREATE TABLE IF NOT EXISTS `c_ai_document_version` (
    `id`            VARCHAR(50)  NOT NULL COMMENT '主键ID',
    `doc_id`        VARCHAR(50)  NOT NULL COMMENT '文档ID',
    `version`       INT          DEFAULT 0 COMMENT '版本号',
    `chunk_count`   INT          DEFAULT 0 COMMENT '该版本知识块数量',
    `snapshot_json` MEDIUMTEXT   COMMENT '知识块快照(JSON数组:[{id,title,content,images}])',
    `create_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `deleted`       INT          DEFAULT 0 COMMENT '逻辑删除: 0=未删除,1=已删除',
    PRIMARY KEY (`id`),
    KEY `idx_doc_version` (`doc_id`, `version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI文档版本快照表';

-- 存量库需手动执行以下 ALTER（启动自动建表只覆盖新表，存量表加列需手工）
-- ALTER TABLE `c_ai_session`   ADD COLUMN `is_pinned`   INT DEFAULT 0 COMMENT '是否置顶: 0=否,1=是' AFTER `deleted`,
--                              ADD COLUMN `is_favorite` INT DEFAULT 0 COMMENT '是否收藏: 0=否,1=是' AFTER `is_pinned`;
-- ALTER TABLE `c_ai_document`  ADD COLUMN `category` VARCHAR(50) DEFAULT NULL COMMENT '文档分类' AFTER `description`,
--                              ADD COLUMN `version`   INT DEFAULT 0 COMMENT '当前版本号' AFTER `deleted`;
