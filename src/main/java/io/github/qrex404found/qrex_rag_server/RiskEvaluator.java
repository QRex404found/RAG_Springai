package io.github.qrex404found.qrex_rag_server;

import org.springframework.stereotype.Component;

@Component
public class RiskEvaluator {

    public enum SecurityPolicy {
        STRICT,  // 정책 B: 의심되면 SUSPICIOUS (보안 우선)
        RELAXED  // 정책 A: 정상 서비스 우선 (편의성)
    }

    // 기본 정책: STRICT(B)
    private SecurityPolicy policy = SecurityPolicy.STRICT;

    public void setPolicy(SecurityPolicy newPolicy) {
        this.policy = newPolicy;
    }

    public String evaluateRisk(String url) {
        if (url == null || url.isBlank()) {
            return "SUSPICIOUS";
        }

        String lower = url.toLowerCase();

        // 1) IP 기반 URL
        if (lower.matches(".*\\d+\\.\\d+\\.\\d+\\.\\d+.*")) {
            return "DANGEROUS";
        }

        // 2) HTTP 사용 → 정책에 따른 판단
        if (lower.startsWith("http://")) {
            if (policy == SecurityPolicy.STRICT) {
                return "SUSPICIOUS";
            }
        }

        // 3) 고위험 서브도메인 패턴
        if (lower.split("\\.").length > 3) {
            if (policy == SecurityPolicy.STRICT) {
                return "SUSPICIOUS";
            }
        }

        // 4) 기본은 SAFE
        return "SAFE";
    }
}
