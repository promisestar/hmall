-- ============================================================
-- hmall 秒杀管理菜单 + 资源初始化 SQL
-- 数据库: hm-admin
-- 执行前提: hm-admin-schema.sql 已执行完毕
-- 注意: 此脚本可安全重复执行（幂等）
-- ============================================================

USE `hm-admin`;

-- ==================== 菜单 ====================

-- 秒杀管理菜单（一级菜单，排序在用户管理之后、系统管理之前）
INSERT INTO `menu` (`id`, `parent_id`, `title`, `level`, `sort`, `name`, `path`, `icon`)
VALUES (10, 0, '秒杀管理', 1, 4, 'SeckillManage', '/admin/seckill', 'AlarmClock')
ON DUPLICATE KEY UPDATE `title` = VALUES(`title`), `path` = VALUES(`path`), `icon` = VALUES(`icon`);

-- 系统管理菜单排序后移（sort 4 → 5）
UPDATE `menu` SET `sort` = 5 WHERE `id` = 5 AND `sort` = 4;

-- 给超级管理员角色分配秒杀管理菜单
INSERT INTO `role_menu_rel` (`role_id`, `menu_id`) VALUES (1, 10)
ON DUPLICATE KEY UPDATE `role_id` = `role_id`;

-- ==================== 资源分类 ====================

INSERT INTO `resource_category` (`name`) VALUES ('秒杀管理')
ON DUPLICATE KEY UPDATE `name` = `name`;

-- ==================== 资源(权限点) ====================

SET @seckill_cat_id = (SELECT `id` FROM `resource_category` WHERE `name` = '秒杀管理' LIMIT 1);

-- 清除旧数据（幂等）
DELETE FROM `resource` WHERE `category_id` = @seckill_cat_id;

INSERT INTO `resource` (`name`, `url`, `method`, `description`, `category_id`) VALUES
('秒杀活动列表', '/admin/seckill/promotion/list', 'GET', '查看秒杀活动列表', @seckill_cat_id),
('秒杀活动详情', '/admin/seckill/promotion/{id}', 'GET', '查看秒杀活动详情', @seckill_cat_id),
('创建秒杀活动', '/admin/seckill/promotion', 'POST', '创建秒杀活动', @seckill_cat_id),
('修改秒杀活动', '/admin/seckill/promotion', 'PUT', '修改秒杀活动', @seckill_cat_id),
('删除秒杀活动', '/admin/seckill/promotion/{id}', 'DELETE', '删除秒杀活动', @seckill_cat_id),
('秒杀场次列表', '/admin/seckill/session/list', 'GET', '查看秒杀场次列表', @seckill_cat_id),
('秒杀场次详情', '/admin/seckill/session/{id}', 'GET', '查看秒杀场次详情', @seckill_cat_id),
('创建秒杀场次', '/admin/seckill/session', 'POST', '创建秒杀场次', @seckill_cat_id),
('修改秒杀场次', '/admin/seckill/session', 'PUT', '修改秒杀场次', @seckill_cat_id),
('删除秒杀场次', '/admin/seckill/session/{id}', 'DELETE', '删除秒杀场次', @seckill_cat_id),
('秒杀商品列表', '/admin/seckill/relation/list', 'GET', '查看秒杀商品列表', @seckill_cat_id),
('秒杀商品详情', '/admin/seckill/relation/{id}', 'GET', '查看秒杀商品详情', @seckill_cat_id),
('创建秒杀商品', '/admin/seckill/relation', 'POST', '创建秒杀商品关联', @seckill_cat_id),
('修改秒杀商品', '/admin/seckill/relation', 'PUT', '修改秒杀商品关联', @seckill_cat_id),
('删除秒杀商品', '/admin/seckill/relation/{id}', 'DELETE', '删除秒杀商品关联', @seckill_cat_id),
('秒杀库存预热', '/admin/seckill/relation/preheat/{id}', 'POST', '手动预热秒杀库存', @seckill_cat_id),
('秒杀订单列表', '/admin/seckill/order/list', 'GET', '查看秒杀订单列表', @seckill_cat_id),
('秒杀库存查询', '/admin/seckill/stock/{relationId}', 'GET', '查看秒杀库存状态', @seckill_cat_id);

-- 清除旧的角色-资源关联（幂等）
DELETE FROM `role_resource_rel` WHERE `resource_id` IN (SELECT `id` FROM `resource` WHERE `category_id` = @seckill_cat_id);

-- 给超级管理员角色分配秒杀管理全部资源
INSERT INTO `role_resource_rel` (`role_id`, `resource_id`)
SELECT 1, `id` FROM `resource` WHERE `category_id` = @seckill_cat_id;
