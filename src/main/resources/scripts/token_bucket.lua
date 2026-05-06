local key           = KEYS[1]
local capacity      = tonumber(ARGV[1])
local refill_rate   = tonumber(ARGV[2])
local now_ms        = tonumber(ARGV[3])
local requested     = tonumber(ARGV[4])

local bucket = redis.call("HMGET", key, "tokens", "last_refill")

local current_tokens
local last_refill_ms

if bucket[1] == false then
    current_tokens = capacity
    last_refill_ms = now_ms
else
    current_tokens = tonumber(bucket[1])
    last_refill_ms = tonumber(bucket[2])
end

local elapsed_seconds = (now_ms - last_refill_ms) / 1000.0
local tokens_to_add   = elapsed_seconds * refill_rate
current_tokens = math.min(capacity, current_tokens + tokens_to_add)

local allowed
local retry_after = 0

if current_tokens >= requested then
    current_tokens = current_tokens - requested
    allowed = 1
else
    local tokens_needed = requested - current_tokens
    retry_after = math.ceil(tokens_needed / refill_rate)
    allowed = 0
end

local ttl_seconds = math.ceil((capacity / refill_rate) * 2)
redis.call("HSET", key,
    "tokens",      tostring(current_tokens),
    "last_refill", tostring(now_ms)
)
redis.call("EXPIRE", key, ttl_seconds)

return { allowed, math.floor(current_tokens), retry_after }