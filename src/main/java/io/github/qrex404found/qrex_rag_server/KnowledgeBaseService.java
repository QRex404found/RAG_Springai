package io.github.qrex404found.qrex_rag_server;

import jakarta.annotation.PostConstruct;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.StringJoiner;
import java.util.stream.Collectors;

@Service
public class KnowledgeBaseService {

    private final ResourcePatternResolver resourceResolver;
    private String allKnowledgeAsText = "";
    private final Set<String> blacklist = new HashSet<>();

    // ⭐️ [추가] 절대 차단하면 안 되는 안전한 사이트 목록 (화이트리스트)
    private final Set<String> whitelist = Set.of(
            "google.com", "www.google.com",
            "naver.com", "www.naver.com",
            "kakao.com", "www.kakao.com",
            "youtube.com", "github.com"
    );

    public KnowledgeBaseService(ResourcePatternResolver resourceResolver) {
        this.resourceResolver = resourceResolver;
    }

    @PostConstruct
    public void init() {
        loadBlacklist();
        loadKnowledgeBase();
    }

    private void loadBlacklist() {
        try {
            Resource resource = resourceResolver.getResource("classpath:/data/blacklist_domains.txt");
            if (resource.exists()) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                    this.blacklist.addAll(reader.lines()
                            .map(String::trim)
                            .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                            .map(String::toLowerCase)
                            .filter(line -> !line.equals("http") && !line.equals("https") && !line.equals("www"))
                            .filter(line -> !line.startsWith("."))
                            .filter(line -> !line.equals("com") && !line.equals("net") && !line.equals("org"))
                            .filter(line -> line.length() > 3)
                            .collect(Collectors.toSet()));
                }
                System.out.println("=== 블랙리스트 로드 완료: " + blacklist.size() + "개 ===");
            }
        } catch (Exception e) {
            System.err.println("블랙리스트 로드 중 오류: " + e.getMessage());
        }
    }

    private void loadKnowledgeBase() {
        try {
            Resource[] files = resourceResolver.getResources("classpath:/data/*.txt");
            StringJoiner sj = new StringJoiner("\n\n---\n\n");

            for (Resource resource : files) {
                if (resource.getFilename() != null && resource.getFilename().contains("blacklist")) continue;
                try (InputStream is = resource.getInputStream()) {
                    String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    sj.add(content);
                }
            }
            this.allKnowledgeAsText = sj.toString();
            System.out.println("=== 지식 베이스 텍스트 로드 완료 ===");
        } catch (Exception e) {
            System.err.println("지식 베이스 로드 중 오류: " + e.getMessage());
        }
    }

    public String getKnowledgeAsText() {
        return this.allKnowledgeAsText;
    }

    public boolean isBlacklisted(String url) {
        if (url == null) return false;
        String lowerUrl = url.toLowerCase();

        // ⭐️ 1. 화이트리스트 먼저 검사 (여기 있으면 무조건 통과!)
        for (String safeDomain : whitelist) {
            if (lowerUrl.contains(safeDomain)) {
                System.out.println("✅ [화이트리스트 통과] " + safeDomain + " 포함됨: " + url);
                return false; // 차단하지 않음 (SAFE)
            }
        }

        // 2. 블랙리스트 검사
        for (String blackEntry : blacklist) {
            if (lowerUrl.contains(blackEntry)) {
                System.out.println("❌ [차단 감지] 입력 URL: " + lowerUrl + " / 매칭된 금지어: '" + blackEntry + "'");
                return true; // 차단함 (DANGEROUS)
            }
        }
        return false;
    }
}