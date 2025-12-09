package io.github.qrex404found.qrex_rag_server;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotBlank;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Configuration
public class AgentTools {

    private final KnowledgeBaseService knowledgeBaseService;
    private final String PC_SERVER_URL = "http://localhost:8080";
    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AgentTools(KnowledgeBaseService knowledgeBaseService) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.restClient = RestClient.create(PC_SERVER_URL);
    }

    // 1. 기본 도구
    @Bean
    @Description("QRex 사용법 안내")
    public Function<String, String> searchUserGuide() {
        return query -> "QRex 이용 가이드 검색 결과:\n" + knowledgeBaseService.searchSimilarDocuments(query);
    }

    // 2. 게시글 검색
    @Bean
    @Description("게시글 검색")
    public Function<String, String> searchCommunityPosts() {
        return keyword -> {
            try {
                return restClient.get()
                        .uri(u -> u.path("/api/posts/search")
                                .queryParam("keyword", keyword)
                                .build())
                        .retrieve()
                        .body(String.class);
            } catch (Exception e) { return "검색 실패: " + e.getMessage(); }
        };
    }

    public record PostRequest(@NotBlank String title, @NotBlank String content, String url, @NotBlank String writerId) {}
    private static final Pattern URL_PATTERN = Pattern.compile("(https?://\\S+)");
    private String extractUrl(String text) { Matcher m = URL_PATTERN.matcher(text); return m.find() ? m.group(1) : null; }
    private int countUrls(String text) { Matcher m = URL_PATTERN.matcher(text); int c=0; while(m.find()) c++; return c; }
    private String removeFoundUrl(String c, String u) { return c.replace(u, "").trim(); }

    @Bean
    @Description("게시글 작성")
    public Function<PostRequest, String> createCommunityPost() {
        return req -> {
            try {
                String title = req.title();
                String content = req.content().trim();
                String url = req.url();
                if (url == null || url.isBlank()) {
                    if (countUrls(content) > 1) return "URL이 여러 개 감지되었습니다.";
                    url = extractUrl(content);
                }
                if (url != null) content = removeFoundUrl(content, url);

                Map<String, Object> body = new HashMap<>();
                body.put("title", title);
                body.put("content", content);
                body.put("writerId", req.writerId());
                body.put("url", url);
                return restClient.post()
                        .uri("/api/posts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(String.class);

            } catch (Exception e) {
                return "작성 실패: " + e.getMessage();
            }
        };
    }

    // 🔥 [최종 수정] 내 최근 게시글 목록 조회 (디버깅 강화)
    @Bean
    @Description("내가 쓴 최근 게시글 목록 조회")
    public Function<String, String> getMyRecentPosts() {
        return userId -> {
            try {
                System.out.println("🔍 [AgentTools] 내 글 목록 조회 시도: User=" + userId);

                // 현재 엔드포인트 사용: /api/posts/myPostsByTitle?title=&requesterId={userId}
                String jsonResponse = restClient.get()
                        .uri(uriBuilder -> uriBuilder
                                .path("/api/posts/myPostsByTitle")
                                .queryParam("title", "")
                                .queryParam("requesterId", userId)
                                .build())
                        .retrieve()
                        .body(String.class);

                if (jsonResponse == null || jsonResponse.isBlank()) return "[]";

                List<Map<String, Object>> posts = objectMapper.readValue(jsonResponse, new TypeReference<>() {});

                // 🔥 [디버깅] 첫 번째 데이터의 키 목록 확인 (JSON에 실제로 어떤 키가 있는지 확인)
                if (!posts.isEmpty()) {
                    System.out.println("🔥 [Key Check] 첫 번째 데이터 키 목록: " + posts.get(0).keySet());
                }

                // 정렬 (최신순)
                posts.sort((a, b) -> {
                    long idA = parseIdSafely(a);
                    long idB = parseIdSafely(b);
                    return Long.compare(idB, idA);
                });

                String sortedIds = posts.stream().map(p -> String.valueOf(parseIdSafely(p))).collect(Collectors.joining(", "));
                System.out.println("🔍 [AgentTools] 정렬된 ID: " + sortedIds);

                return objectMapper.writeValueAsString(posts);

            } catch (Exception e) {
                System.err.println("🔥 [AgentTools] 목록 조회 실패: " + e.getMessage());
                return "[]";
            }
        };
    }

    // [Helper] ID 추출 (boardId, id, postId 다 찾아봄)
    private long parseIdSafely(Map<String, Object> map) {
        if (map == null) return 0L;
        Object val = map.get("boardId");
        if (val == null) val = map.get("id");
        if (val == null) val = map.get("postId");

        try {
            return val != null ? Long.parseLong(String.valueOf(val)) : 0L;
        } catch (Exception e) { return 0L; }
    }

    public record FindMyPostsRequest(@NotBlank String title, @NotBlank String requesterId) {}
    @Bean
    public Function<FindMyPostsRequest, String> findMyPostsByTitle() {
        return req -> {
            try {
                String json = restClient.get()
                        .uri(u -> u.path("/api/posts/myPostsByTitle")
                                .queryParam("title", req.title())
                                .queryParam("requesterId", req.requesterId())
                                .queryParam("exact", true)
                                .build())
                        .retrieve()
                        .body(String.class);

                List<Map<String, Object>> posts = objectMapper.readValue(json, new TypeReference<>(){});
                posts.sort((a, b) -> Long.compare(parseIdSafely(b), parseIdSafely(a)));
                return objectMapper.writeValueAsString(posts);
            } catch (Exception e) { return "[]"; }
        };
    }

    // ---------------- 게시글 삭제 ----------------

    public record DeletePostByIdRequest(Integer postId, String requesterId) {}
    @Bean
    @Description("게시글 ID 기반 삭제")
    public Function<DeletePostByIdRequest, String> deletePostById() {
        return req -> {
            try {
                Map<String, Object> body = new HashMap<>();
                body.put("postId", req.postId());
                body.put("requesterId", req.requesterId());

                return restClient.post()
                        .uri("/api/posts/deleteById")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(String.class);

            } catch (Exception e) {
                return "삭제 실패 "+e.getMessage();
            }
        };
    }

    // ---------------- AI 분석기록 조회 ----------------

    @Bean
    @Description("분석 기록 조회")
    public Function<String, String> getAnalysisHistory() {
        return userId -> {
            try {
                return restClient.get()
                        .uri(u -> u.path("/api/analysis/ai/history")
                                .queryParam("writerId", userId)
                                .build())
                        .retrieve()
                        .body(String.class);

            } catch (Exception e) { return "[]";}
        };
    }

    // **** 중요: 수정된 DTO (userId 포함) ****
    public record UpdateAnalysisTitleRequest(
            String analysisId,
            String newTitle,
            String userId // 👈 AgentController에서 전달하는 userId 필드
    ) {}

    @Bean
    @Description("분석 기록 제목 수정 (PATCH 고정)")
    public Function<UpdateAnalysisTitleRequest, String> updateAnalysisTitle() {
        return req -> {
            try {
                System.out.println("🔍 [updateAnalysisTitle] 호출됨");
                System.out.println("    - analysisId = " + req.analysisId());
                System.out.println("    - newTitle   = " + req.newTitle());
                System.out.println("    - userId     = " + req.userId());

                Map<String, String> body = new HashMap<>();
                body.put("analysisId", req.analysisId());
                body.put("newTitle", req.newTitle());
                body.put("userId", req.userId());

                System.out.println("📦 [updateAnalysisTitle] 전송 body = " + body);

                var responseEntity = restClient.patch()
                        .uri("/api/analysis/ai/title")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .toBodilessEntity(); // HTTP 2xx 상태만 확인

                System.out.println("✅ [updateAnalysisTitle] 응답 status = " + responseEntity.getStatusCode());

                return "성공";

            } catch (Exception e) {
                System.err.println("❌ [updateAnalysisTitle] 요청 실패");
                // HTTP 4xx/5xx면 여기로 들어옴
                if (e instanceof org.springframework.web.client.HttpClientErrorException httpEx) {
                    System.err.println("    - status = " + httpEx.getStatusCode());
                    System.err.println("    - responseBody = " + httpEx.getResponseBodyAsString());
                } else if (e instanceof org.springframework.web.client.HttpServerErrorException httpEx) {
                    System.err.println("    [5xx] status = " + httpEx.getStatusCode());
                    System.err.println("    [5xx] responseBody = " + httpEx.getResponseBodyAsString());
                } else {
                    System.err.println("    - message = " + e.getMessage());
                }
                e.printStackTrace();
                return "실패: " + e.getMessage();
            }
        };
    }
}