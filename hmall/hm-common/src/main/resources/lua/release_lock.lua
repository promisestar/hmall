-- 原子释放分布式锁：只有持有者才能释放
-- KEYS[1] = lock key
-- ARGV[1] = lock value (UUID)

if redis.call('get', KEYS[1]) == ARGV[1] then
    return redis.call('del', KEYS[1])
else
    return 0
end
