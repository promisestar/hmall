-- 滑动窗口限流 Lua 脚本
-- 基于 Redis ZSET 实现精确滑动窗口，保证 ZREMRANGEBYSCORE + ZCARD + ZADD + PEXPIRE 原子执行
--
-- KEYS[1] = 限流 key（如 ratelimit:/seckill/order:123）
-- ARGV[1] = 当前时间戳（毫秒）
-- ARGV[2] = 窗口大小（毫秒）
-- ARGV[3] = 窗口内最大请求数
-- ARGV[4] = 唯一请求 ID（UUID，用于 ZADD member 去重）
--
-- 返回值：1 = 允许通过，0 = 限流拒绝

local key = KEYS[1]
local now = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local maxRequests = tonumber(ARGV[3])
local requestId = ARGV[4]

-- 1. 移除窗口外的过期记录
local cutoff = now - window
redis.call('ZREMRANGEBYSCORE', key, '-inf', cutoff)

-- 2. 统计当前窗口内的请求数
local count = redis.call('ZCARD', key)

if count < maxRequests then
    -- 3. 未超限：添加本次请求记录
    redis.call('ZADD', key, now, requestId)
    -- 4. 设置 key 过期时间（窗口大小 + 冗余），防止 key 永久残留
    redis.call('PEXPIRE', key, window + 1000)
    return 1
else
    -- 超限：拒绝
    return 0
end
