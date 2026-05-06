local key       = KEYS[1]
local limit     = tonumber(ARGV[1])
local window_ms = tonumber(ARGV[2])
local now_ms    = tonumber(ARGV[3])

local window_start = now_ms - window_ms

redis.call("ZREMRANGEBYSCORE", key, "-inf", window_start)

local current_count = redis.call("ZCARD", key)

local allowed
local retry_after = 0

if current_count < limit then
    local unique_member = now_ms .. "-" .. math.random(1000000)
    redis.call("ZADD", key, now_ms, unique_member)
    current_count = current_count + 1
    allowed = 1
else
    local oldest = redis.call("ZRANGE", key, 0, 0, "WITHSCORES")
    if oldest and oldest[2] then
        local oldest_ts = tonumber(oldest[2])
        retry_after = math.ceil((oldest_ts + window_ms - now_ms) / 1000)
        retry_after = math.max(1, retry_after)
    else
        retry_after = math.ceil(window_ms / 1000)
    end
    allowed = 0
end

local ttl_seconds = math.ceil(window_ms / 1000) + 10
redis.call("EXPIRE", key, ttl_seconds)

return { allowed, current_count, retry_after }