# admin-service 网关配置说明

## 1. Nacos gateway-routes.json 添加 admin-service 路由

在 Nacos 配置中心的 `gateway-routes.json` 中**追加**以下路由项（保留现有路由不变）：

```json
{
  "id": "admin-service",
  "predicates": [{
    "name": "Path",
    "args": { "pattern": "/admin/**" }
  }],
  "filters": [],
  "uri": "lb://admin-service",
  "order": 0
}
```

## 2. Nacos gateway 配置添加白名单

在 Nacos 的 gateway 配置（`gateway-dev.yaml`）中，将 `/admin/**` 添加到 `hm.auth.excludePaths`，让网关对 admin 路径不做 C 端 JWT 校验（admin JWT 校验由 admin-service 内部拦截器完成）：

```yaml
hm:
  auth:
    excludePaths:
      - /admin/**                    # 新增：admin 路径由 admin-service 自行校验
      # ... 保留现有白名单 ...
```

## 3. Nacos 新增 shared-redis.yaml（如果不存在）

admin-service 需要连接 Redis，如果 Nacos 中没有 `shared-redis.yaml`，请新建：

```yaml
spring:
  redis:
    host: 192.168.100.128
    port: 6379
    password: ${REDIS_PASSWORD:}
    database: 0
    timeout: 5000ms
    lettuce:
      pool:
        max-active: 8
        max-idle: 8
        min-idle: 0
```

然后可以在 admin-service 的 bootstrap.yml shared-configs 中引用它。

## 4. 执行建表 SQL

在 MySQL 中执行 `admin-service/src/main/resources/hm-admin-schema.sql` 创建 `hm-admin` 数据库和 RBAC 表。

## 5. 默认管理员账号

- 用户名: `admin`
- 密码: `admin123`
- 密钥库密码: `admin123`（admin.jks）
