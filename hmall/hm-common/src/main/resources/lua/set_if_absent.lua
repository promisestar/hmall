-- SET NX EX：仅当 key 不存在时设置，带过期时间
-- KEYS[1] = cache key
-- ARGV[1] = value
-- ARGV[2] = expire seconds
-- 返回："OK" 成功，nil 失败（key 已存在）
-- 注意：ARGV[2] 需用 tonumber() 显式转换，因为 Java 端传入的 String
--       经 Jackson2Json 序列化后到 Lua 侧仍为字符串，SET EX 要求整数

return redis.call('SET', KEYS[1], ARGV[1], 'NX', 'EX', tonumber(ARGV[2]))
