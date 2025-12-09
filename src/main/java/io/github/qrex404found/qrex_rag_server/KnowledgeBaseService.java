package io.github.qrex404found.qrex_rag_server;

import jakarta.annotation.PostConstruct;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class KnowledgeBaseService {

    private final ResourcePatternResolver resourceResolver;
    private final VectorStore vectorStore; // ✅ ChromaDB 연동 객체
    private final Set<String> blacklist = new HashSet<>();

    // 화이트리스트 (기존 유지)
    private final Set<String> whitelist = Set.of(
            "google.com", "www.google.com",
            "naver.com", "www.naver.com",
            "kakao.com", "www.kakao.com",
            "youtube.com", "github.com"
    );

    public KnowledgeBaseService(ResourcePatternResolver resourceResolver, VectorStore vectorStore) {
        this.resourceResolver = resourceResolver;
        this.vectorStore = vectorStore;
    }

    @PostConstruct
    public void init() {
        loadBlacklist();
        loadKnowledgeBaseToChroma(); // ✅ DB 적재 시작
    }

    // 1. 텍스트 파일 -> ChromaDB 적재 (최초 1회만 수행되도록 체크)
    private void loadKnowledgeBaseToChroma() {
        try {
            // ⭐️ 이미 데이터가 있는지 확인 (재시작 시 중복 방지)
            // '피싱'이라는 단어로 검색해보고 데이터가 있으면 적재 건너뜀
            List<Document> check = vectorStore.similaritySearch("피싱");
            if (!check.isEmpty()) {
                System.out.println("✅ ChromaDB에 이미 데이터가 존재합니다. (초기화 스킵)");
                return;
            }

            System.out.println("🚀 ChromaDB 데이터 적재 시작...");
            Resource[] files = resourceResolver.getResources("classpath:/data/*.txt");

            // 텍스트를 적당한 크기로 자르는 도구 (토큰 기준)
            TokenTextSplitter splitter = new TokenTextSplitter();

            for (Resource resource : files) {
                // 블랙리스트 파일은 벡터화 제외 (룰 기반으로 쓸 거니까)
                if (resource.getFilename() != null && resource.getFilename().contains("blacklist")) continue;

                System.out.println("📄 처리 중: " + resource.getFilename());

                // 파일 읽기
                TextReader reader = new TextReader(resource);
                List<Document> documents = reader.get();

                // 자르기 (Chunking) 및 DB 저장
                List<Document> splitDocs = splitter.apply(documents);
                vectorStore.add(splitDocs);
            }
            System.out.println("🎉 ChromaDB 데이터 적재 완료!");

        } catch (Exception e) {
            System.err.println("❌ 데이터 적재 실패: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // 2. RAG 핵심: 유사도 검색 (이제 전체 텍스트 대신 이걸 씁니다)
    public String searchSimilarDocuments(String query) {
        // 질문과 관련된 상위 문서 조각만 가져옵니다.
        List<Document> results = vectorStore.similaritySearch(query);

        if (results.isEmpty()) {
            return "";
        }

        // 검색된 조각들을 하나의 문자열로 합침
        return results.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n---\n\n"));
    }

    // 3. 블랙리스트 로드 (기존 코드 유지)
    private void loadBlacklist() {
        try {
            Resource resource = resourceResolver.getResource("classpath:/data/blacklist_domains.txt");
            if (resource != null && resource.exists()) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                    this.blacklist.addAll(reader.lines()
                            .map(String::trim)
                            .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                            .map(String::toLowerCase)
                            .filter(line -> !line.equals("http") && !line.equals("https") && !line.equals("www")) // 필터링 유지
                            .collect(Collectors.toSet()));
                }
                System.out.println("=== 블랙리스트 로드 완료: " + blacklist.size() + "개 ===");
            }
        } catch (Exception e) {}
    }

    public boolean isBlacklisted(String url) {
        if (url == null) return false;
        String lowerUrl = url.toLowerCase();

        // 1. 화이트리스트 먼저 검사
        for (String safeDomain : whitelist) {
            if (lowerUrl.contains(safeDomain)) {
                return false;
            }
        }

        // 2. 블랙리스트 검사
        for (String blackEntry : blacklist) {
            if (lowerUrl.contains(blackEntry)) {
                return true;
            }
        }
        return false;
    }
}