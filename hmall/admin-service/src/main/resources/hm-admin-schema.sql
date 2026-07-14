-- ============================================================
-- hmall 管理后台 RBAC 建表 SQL
-- 数据库: hm-admin
-- ============================================================

CREATE DATABASE IF NOT EXISTS `hm-admin` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE `hm-admin`;

-- 管理员表
CREATE TABLE IF NOT EXISTS `admin_user` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `username`    VARCHAR(64)  NOT NULL COMMENT '用户名',
  `password`    VARCHAR(128) NOT NULL COMMENT '密码(BCrypt)',
  `icon`        VARCHAR(512) DEFAULT NULL COMMENT '头像',
  `email`       VARCHAR(128) DEFAULT NULL COMMENT '邮箱',
  `nick_name`   VARCHAR(64)  DEFAULT NULL COMMENT '昵称',
  `note`        VARCHAR(256) DEFAULT NULL COMMENT '备注',
  `status`      INT          DEFAULT 1    COMMENT '状态: 0禁用 1启用',
  `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP,
  `login_time`  DATETIME     DEFAULT NULL COMMENT '最后登录时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='后台管理员表';

-- 角色表
CREATE TABLE IF NOT EXISTS `role` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `name`        VARCHAR(64)  NOT NULL COMMENT '角色名称',
  `description` VARCHAR(256) DEFAULT NULL,
  `admin_count` INT          DEFAULT 0  COMMENT '关联管理员数',
  `status`      INT          DEFAULT 1  COMMENT '状态: 0禁用 1启用',
  `sort`        INT          DEFAULT 0,
  `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='后台角色表';

-- 菜单表
CREATE TABLE IF NOT EXISTS `menu` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `parent_id`   BIGINT       DEFAULT 0  COMMENT '父级ID, 0为根',
  `title`       VARCHAR(64)  NOT NULL COMMENT '菜单名称',
  `level`       INT          DEFAULT 1  COMMENT '菜单级数',
  `sort`        INT          DEFAULT 0,
  `name`        VARCHAR(64)  DEFAULT NULL COMMENT '前端路由名称',
  `path`        VARCHAR(128) DEFAULT NULL COMMENT '前端路由路径',
  `icon`        VARCHAR(128) DEFAULT NULL COMMENT '前端图标',
  `hidden`      INT          DEFAULT 0  COMMENT '是否隐藏: 0显示 1隐藏',
  `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='后台菜单表';

-- 资源分类表
CREATE TABLE IF NOT EXISTS `resource_category` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `name`        VARCHAR(64)  NOT NULL,
  `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资源分类表';

-- 资源表(权限点)
CREATE TABLE IF NOT EXISTS `resource` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `name`        VARCHAR(64)  NOT NULL COMMENT '资源名称',
  `url`         VARCHAR(256) NOT NULL COMMENT '资源URL(Ant路径)',
  `method`      VARCHAR(16)  DEFAULT NULL COMMENT 'HTTP方法',
  `description` VARCHAR(256) DEFAULT NULL,
  `category_id` BIGINT       DEFAULT NULL COMMENT '资源分类ID',
  `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_category` (`category_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='后台资源(权限)表';

-- 管理员-角色关联表
CREATE TABLE IF NOT EXISTS `admin_user_role_rel` (
  `id`            BIGINT NOT NULL AUTO_INCREMENT,
  `admin_user_id` BIGINT NOT NULL,
  `role_id`       BIGINT NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_admin_role` (`admin_user_id`, `role_id`),
  KEY `idx_role` (`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='管理员-角色关联表';

-- 角色-菜单关联表
CREATE TABLE IF NOT EXISTS `role_menu_rel` (
  `id`       BIGINT NOT NULL AUTO_INCREMENT,
  `role_id`  BIGINT NOT NULL,
  `menu_id`  BIGINT NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_menu` (`role_id`, `menu_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色-菜单关联表';

-- 角色-资源关联表
CREATE TABLE IF NOT EXISTS `role_resource_rel` (
  `id`          BIGINT NOT NULL AUTO_INCREMENT,
  `role_id`     BIGINT NOT NULL,
  `resource_id` BIGINT NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_resource` (`role_id`, `resource_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色-资源关联表';

-- ============================================================
-- 初始化数据
-- ============================================================

-- 超级管理员 (密码: admin123, BCrypt 加密结果: 60 字符)
INSERT INTO `admin_user` (`username`, `password`, `nick_name`, `note`, `status`)
VALUES ('admin', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', '超级管理员', '系统初始管理员', 1)
ON DUPLICATE KEY UPDATE `username` = `username`;

-- 超级管理员角色
INSERT INTO `role` (`name`, `description`, `admin_count`, `status`, `sort`)
VALUES ('超级管理员', '拥有全部权限', 1, 1, 0);

-- 关联超级管理员与角色
INSERT INTO `admin_user_role_rel` (`admin_user_id`, `role_id`) VALUES (1, 1);

-- ============================================================
-- 菜单初始化数据
-- ============================================================
INSERT INTO `menu` (`id`, `parent_id`, `title`, `level`, `sort`, `name`, `path`, `icon`) VALUES
(1, 0, '数据概览', 1, 0, 'Dashboard', '/admin/dashboard', 'DataAnalysis'),
(2, 0, '商品管理', 1, 1, 'ItemManage', '/admin/items', 'Goods'),
(3, 0, '订单管理', 1, 2, 'OrderManage', '/admin/orders', 'Tickets'),
(4, 0, '用户管理', 1, 3, 'UserManage', '/admin/users', 'UserFilled'),
(5, 0, '系统管理', 1, 4, 'System', '', 'Setting'),
(6, 5, '管理员管理', 2, 0, 'AdminUserManage', '/admin/system/admin', 'User'),
(7, 5, '角色管理', 2, 1, 'RoleManage', '/admin/system/role', 'UserFilled'),
(8, 5, '菜单管理', 2, 2, 'MenuManage', '/admin/system/menu', 'Menu'),
(9, 5, '资源管理', 2, 3, 'ResourceManage', '/admin/system/resource', 'Setting');

-- 给超级管理员角色分配全部菜单
INSERT INTO `role_menu_rel` (`role_id`, `menu_id`) VALUES
(1, 1), (1, 2), (1, 3), (1, 4), (1, 5), (1, 6), (1, 7), (1, 8), (1, 9);

-- ============================================================
-- 资源分类初始化
-- ============================================================
INSERT INTO `resource_category` (`name`) VALUES
('商品管理'), ('订单管理'), ('用户管理'), ('系统管理');

-- ============================================================
-- 资源(权限点)初始化
-- ============================================================
INSERT INTO `resource` (`name`, `url`, `method`, `description`, `category_id`) VALUES
('商品列表', '/admin/product/list', 'GET', '查看商品列表', 1),
('商品详情', '/admin/product/{id}', 'GET', '查看商品详情', 1),
('新增商品', '/admin/product', 'POST', '新增商品', 1),
('修改商品', '/admin/product/{id}', 'PUT', '修改商品信息', 1),
('商品上下架', '/admin/product/publishStatus', 'POST', '批量上下架商品', 1),
('删除商品', '/admin/product', 'DELETE', '批量删除商品', 1),
('订单列表', '/admin/order/list', 'GET', '查看订单列表', 2),
('订单详情', '/admin/order/{id}', 'GET', '查看订单详情', 2),
('订单发货', '/admin/order/delivery', 'POST', '批量发货', 2),
('关闭订单', '/admin/order/close', 'POST', '批量关闭订单', 2),
('修改备注', '/admin/order/note', 'POST', '修改订单备注', 2),
('用户列表', '/admin/member/list', 'GET', '查看C端用户列表', 3),
('用户详情', '/admin/member/{id}', 'GET', '查看C端用户详情', 3),
('修改用户状态', '/admin/member/status/{id}', 'POST', '冻结/解冻用户', 3),
('调整用户余额', '/admin/member/balance/{id}', 'POST', '调整用户余额', 3),
('管理员列表', '/admin/admin/list', 'GET', '查看管理员列表', 4),
('管理员详情', '/admin/admin/{id}', 'GET', '查看管理员详情', 4),
('新增管理员', '/admin/admin', 'POST', '新增管理员', 4),
('更新管理员', '/admin/admin/update/{id}', 'POST', '更新管理员信息', 4),
('删除管理员', '/admin/admin/delete/{id}', 'POST', '删除管理员', 4),
('分配角色', '/admin/admin/role/update', 'POST', '给管理员分配角色', 4),
('角色列表', '/admin/role/list', 'GET', '查看角色列表', 4),
('新增角色', '/admin/role/create', 'POST', '新增角色', 4),
('更新角色', '/admin/role/update/{id}', 'POST', '更新角色', 4),
('删除角色', '/admin/role/delete', 'POST', '批量删除角色', 4),
('分配菜单', '/admin/role/allocMenu', 'POST', '给角色分配菜单', 4),
('分配资源', '/admin/role/allocResource', 'POST', '给角色分配资源', 4),
('菜单树', '/admin/menu/tree', 'GET', '获取菜单树', 4),
('新增菜单', '/admin/menu/create', 'POST', '新增菜单', 4),
('更新菜单', '/admin/menu/update/{id}', 'POST', '更新菜单', 4),
('删除菜单', '/admin/menu/delete/{id}', 'POST', '删除菜单', 4),
('资源列表', '/admin/resource/list', 'GET', '查看资源列表', 4),
('新增资源', '/admin/resource/create', 'POST', '新增资源', 4),
('更新资源', '/admin/resource/update/{id}', 'POST', '更新资源', 4),
('删除资源', '/admin/resource/delete/{id}', 'POST', '删除资源', 4);

-- 给超级管理员角色分配全部资源
INSERT INTO `role_resource_rel` (`role_id`, `resource_id`)
SELECT 1, `id` FROM `resource`;
