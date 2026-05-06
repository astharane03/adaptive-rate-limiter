package com.ratelimiter.adaptive_rate_limiter.controller;

import com.ratelimiter.adaptive_rate_limiter.model.RateLimitRule;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime rule management API.
 *
 * Allows you to create, view, update, and delete rate limit rules
 * without restarting the application.
 *
 * Phase 8 will persist rules in Redis so they survive restarts.
 * For now, rules live in an in-memory map.
 *
 * Endpoints:
 *   GET    /admin/rules          → list all rules
 *   GET    /admin/rules/{id}     → get one rule
 *   POST   /admin/rules          → create new rule
 *   DELETE /admin/rules/{id}     → delete a rule
 *   GET    /admin/health         → gateway health summary
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    // In-memory store — replaced with Redis in Phase 8
    private final Map<String, RateLimitRule> rules = new ConcurrentHashMap<>();

    // ── List all rules ────────────────────────────────────────

    @GetMapping("/rules")
    public ResponseEntity<?> listRules() {
        if (rules.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                    "rules", rules.values(),
                    "count", 0,
                    "message", "No custom rules. Default tier-based rules are active."
            ));
        }
        return ResponseEntity.ok(Map.of(
                "rules", rules.values(),
                "count", rules.size()
        ));
    }

    // ── Get one rule ──────────────────────────────────────────

    @GetMapping("/rules/{id}")
    public ResponseEntity<?> getRule(@PathVariable String id) {
        RateLimitRule rule = rules.get(id);
        if (rule == null) {
            return ResponseEntity.status(404).body(Map.of(
                    "error", "Rule not found",
                    "id", id
            ));
        }
        return ResponseEntity.ok(rule);
    }

    // ── Create rule ───────────────────────────────────────────

    @PostMapping("/rules")
    public ResponseEntity<?> createRule(@RequestBody RateLimitRule rule) {

        // Validate required fields
        if (rule.getRequestsPerWindow() <= 0) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "requestsPerWindow must be greater than 0"
            ));
        }
        if (rule.getWindowSizeSeconds() <= 0) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "windowSizeSeconds must be greater than 0"
            ));
        }

        // Auto-assign ID if not provided
        String id = (rule.getId() != null && !rule.getId().isBlank())
                ? rule.getId()
                : UUID.randomUUID().toString().substring(0, 8);

        RateLimitRule saved = RateLimitRule.builder()
                .id(id)
                .clientKey(rule.getClientKey() != null ? rule.getClientKey() : "*")
                .pathPattern(rule.getPathPattern() != null ? rule.getPathPattern() : "*")
                .requestsPerWindow(rule.getRequestsPerWindow())
                .windowSizeSeconds(rule.getWindowSizeSeconds())
                .burstCapacity(rule.getBurstCapacity())
                .algorithm(rule.getAlgorithm() != null
                        ? rule.getAlgorithm()
                        : RateLimitRule.Algorithm.TOKEN_BUCKET)
                .shadowMode(rule.isShadowMode())
                .enabled(true)
                .createdAt(Instant.now())
                .description(rule.getDescription())
                .build();

        rules.put(id, saved);

        return ResponseEntity.status(201).body(Map.of(
                "message", "Rule created",
                "rule", saved
        ));
    }

    // ── Delete rule ───────────────────────────────────────────

    @DeleteMapping("/rules/{id}")
    public ResponseEntity<?> deleteRule(@PathVariable String id) {
        RateLimitRule removed = rules.remove(id);
        if (removed == null) {
            return ResponseEntity.status(404).body(Map.of(
                    "error", "Rule not found",
                    "id", id
            ));
        }
        return ResponseEntity.ok(Map.of(
                "message", "Rule deleted",
                "id", id
        ));
    }

    // ── Health summary ────────────────────────────────────────

    @GetMapping("/health")
    public ResponseEntity<?> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("activeRules", rules.size());
        body.put("timestamp", Instant.now().toString());
        return ResponseEntity.ok(body);
    }
}