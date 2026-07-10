-- 原子加购 Lua 脚本
-- KEYS[1] = cart:user:{userId}       商品元数据 Hash
-- KEYS[2] = cart:user:{userId}:num   数量 Hash
-- KEYS[3] = cart:user:{userId}:v     全局版本号
-- ARGV[1] = itemId                   商品 ID
-- ARGV[2] = itemDataJson             商品元数据 JSON（不含 num）
-- ARGV[3] = ttlSeconds               过期时间（秒）
-- ARGV[4] = maxItems                 购物车最大商品数
-- ARGV[5] = version                  写入时间戳
-- 返回：-1 = 购物车已满，>= 0 = 当前数量

local exists = redis.call('HEXISTS', KEYS[1], ARGV[1])

if exists == 1 then
    -- 已有商品：HINCRBY 原子递增数量
    local newNum = redis.call('HINCRBY', KEYS[2], ARGV[1], 1)
    redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
    redis.call('SET', KEYS[3], ARGV[5])
    redis.call('EXPIRE', KEYS[1], tonumber(ARGV[3]))
    redis.call('EXPIRE', KEYS[2], tonumber(ARGV[3]))
    redis.call('EXPIRE', KEYS[3], tonumber(ARGV[3]))
    return newNum
else
    -- 新商品：检查上限后写入（or 0 防御 HLEN 返回 nil）
    local size = redis.call('HLEN', KEYS[1]) or 0
    if size >= tonumber(ARGV[4]) then
        return -1
    end
    redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
    redis.call('HSET', KEYS[2], ARGV[1], 1)
    redis.call('SET', KEYS[3], ARGV[5])
    redis.call('EXPIRE', KEYS[1], tonumber(ARGV[3]))
    redis.call('EXPIRE', KEYS[2], tonumber(ARGV[3]))
    redis.call('EXPIRE', KEYS[3], tonumber(ARGV[3]))
    return 1
end
