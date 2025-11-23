package io.github.qrex404found.qrex_rag_server;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.context.ApplicationContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

@RestController
public class AgentController {

    private final ChatClient chatClient;
    private final ApplicationContext applicationContext;

    // 🔥 대화별로 posts 결과 저장하는 캐시 (게시글 삭제 시 번호 선택용)
    private final Map<String, List<Integer>> lastFoundPosts = new ConcurrentHashMap<>();

    private final Map<String, List<Message>> conversationStore = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AgentController(ChatClient chatClient, ApplicationContext applicationContext) {
        this.chatClient = chatClient;
        this.applicationContext = applicationContext;
    }

    // ===========================
    // 🔥 extractUserId()
    // ===========================
    private String extractUserId(String userMessageContent, String userIdParam) {
        if (userIdParam != null &&
                !userIdParam.isBlank() &&
                !"null".equals(userIdParam) &&
                !"undefined".equals(userIdParam)) {
            return userIdParam;
        }

        try {
            for (String line : userMessageContent.split("\n")) {
                if (line.trim().startsWith("- 사용자 ID:")) {
                    String parsed = line.replace("- 사용자 ID:", "").trim();
                    if (!parsed.isEmpty() &&
                            !"null".equals(parsed) &&
                            !"undefined".equals(parsed)) {
                        return parsed;
                    }
                }
            }
        } catch (Exception ignored) {}

        return "guest";
    }

    // ===========================
    // Tool JSON 추출
    // ===========================
    private String extractToolJson(String text) {
        if (text == null) return null;
        String trimmed = text.trim();

        if (trimmed.startsWith("{") && trimmed.endsWith("}") && trimmed.contains("tool_name")) {
            return trimmed;
        }

        if (trimmed.contains("```")) {
            String sanitized = trimmed.replaceAll("```json", "").replaceAll("```", "").trim();
            if (sanitized.startsWith("{") && sanitized.contains("tool_name")) {
                return sanitized;
            }
        }

        try {
            int idx = text.indexOf("\"tool_name\"");
            if (idx == -1) idx = text.indexOf("'tool_name'");
            if (idx == -1) return null;

            int start = -1;
            for (int i = idx; i >= 0; i--) {
                if (text.charAt(i) == '{') { start = i; break; }
            }

            int count = 0;
            for (int i = start; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '{') count++;
                else if (c == '}') count--;
                if (count == 0) return text.substring(start, i + 1);
            }

        } catch (Exception ignored) {}

        return null;
    }

    // ===========================
    // Chat Endpoint
    // ===========================
    @GetMapping("/api/agent/chat")
    public String chat(
            @RequestParam("message") String message,
            @RequestParam(value = "isLoggedIn", defaultValue = "false") boolean isLoggedIn,
            @RequestParam(value = "conversationId", defaultValue = "default") String conversationId,
            @RequestParam(value = "userId", required = false) String userId
    ) {

        boolean effectiveLoggedIn =
                isLoggedIn || (userId != null && !userId.isEmpty() && !"null".equals(userId));

        List<Message> history =
                conversationStore.getOrDefault(conversationId, new ArrayList<>());

        String guestRules = """
                [SYSTEM RULES - GUEST]
                - Guest cannot create/delete/report posts.
                - ALWAYS answer ONLY: "게시글을 작성하려면 로그인이 필요합니다."
                """;

        String loggedInRules = """
                [SYSTEM RULES - LOGGED IN USER]
                You are QRex AI Agent.

                [Tools]
                - createCommunityPost(title, content, writerId)
                - findMyPostsByTitle(title, requesterId)
                - deletePostById(postId, requesterId)
                - searchCommunityPosts(keyword)
                - updateAnalysisTitle(analysisId, newTitle)
                - getAnalysisHistory(writerId)
                - getMyRecentPosts(userId)

                [Instruction]
                1. **Post Deletion**:
                    ALWAYS follow this rule:
                    - "최근 게시글 삭제": Call `getMyRecentPosts` -> Select 1st ID -> Call `deletePostById`.
                    - "제목으로 삭제": Call `findMyPostsByTitle`. If multiple, ask user. If one, delete.
                """;

        String ruleBlock = effectiveLoggedIn ? loggedInRules : guestRules;

        String finalUserMessage = """
                %s

                [사용자 정보]
                - 로그인 상태: %s
                - 사용자 ID: %s

                [사용자 메시지]
                %s
                """.formatted(ruleBlock, effectiveLoggedIn, userId, message);

        String currentUserId = extractUserId(finalUserMessage, userId);

        try {
            String response = null;

            // ===========================
            // 🔥 사용자가 "1번 삭제해줘" 같은 번호만 말했는지 체크
            // ===========================
            if (lastFoundPosts.containsKey(conversationId)) {
                List<Integer> list = lastFoundPosts.get(conversationId);
                if (message.matches("^[0-9]+번.*") || message.matches("^[0-9]+$")) {
                    int index = Integer.parseInt(message.replaceAll("[^0-9]", ""));
                    if (index >= 1 && index <= list.size()) {
                        int realPostId = list.get(index - 1);
                        response = """
                                {
                                  "tool_name": "deletePostById",
                                  "parameters": {
                                    "postId": %d,
                                    "requesterId": "%s"
                                  }
                                }
                                """.formatted(realPostId, currentUserId);
                    }
                }
            }

            // ===========================
            // AI 호출 (번호 선택이 아닐 경우)
            // ===========================
            if (response == null) {
                List<Message> msgs = new ArrayList<>(history);
                msgs.add(new UserMessage(finalUserMessage));
                response = chatClient.prompt().messages(msgs).call().content();
            }

            int loop = 0;
            final int MAX = 5;

            // ===========================
            // 도구 실행 루프
            // ===========================
            while (extractToolJson(response) != null && loop < MAX) {
                loop++;
                String json = extractToolJson(response);
                objectMapper.readTree(json); // 유효성 검사

                String toolResult = executeTool(json, finalUserMessage, userId);

                // 🔥 findMyPosts / getMyRecentPosts 결과를 저장 (삭제 시 번호 선택용)
                if (json.contains("findMyPostsByTitle") || json.contains("getMyRecentPosts")) {
                    try {
                        List<Map<String, Object>> arr = objectMapper.readValue(toolResult, List.class);
                        List<Integer> ids = new ArrayList<>();
                        for (Map<String, Object> m : arr) {
                            ids.add((Integer) m.get("boardId"));
                        }
                        lastFoundPosts.put(conversationId, ids);
                    } catch (Exception ignored) {}
                }

                history.add(new UserMessage(finalUserMessage));
                history.add(new AssistantMessage(json));
                history.add(new UserMessage("[TOOL_RESULT] " + toolResult));

                response = chatClient.prompt().messages(history).call().content();
            }

            history.add(new AssistantMessage(response));
            saveHistory(conversationId, history);

            return response;

        } catch (Exception e) {
            e.printStackTrace();
            return "죄송합니다. 시스템 오류가 발생했습니다.";
        }
    }

    // ===========================
    // Tool 실행
    // ===========================
    private String executeTool(String rawJson, String userMessageContent, String userIdParam) throws Exception {

        Map<String, Object> map = objectMapper.readValue(rawJson, Map.class);
        String toolName = String.valueOf(map.get("tool_name"));
        Map<String, Object> params = (Map<String, Object>) map.get("parameters");

        String currentUserId = extractUserId(userMessageContent, userIdParam);

        switch (toolName) {

            case "createCommunityPost" -> {
                String title = String.valueOf(params.get("title"));
                String content = String.valueOf(params.get("content"));
                Function<AgentTools.PostRequest, String> func =
                        applicationContext.getBean("createCommunityPost", Function.class);
                // [수정] 4개 인자 전달 (url은 null)
                return func.apply(new AgentTools.PostRequest(title, content, null, currentUserId));
            }

            case "searchCommunityPosts" -> {
                Function<String, String> func =
                        applicationContext.getBean("searchCommunityPosts", Function.class);
                return func.apply(String.valueOf(params.get("keyword")));
            }

            case "findMyPostsByTitle" -> {
                String title = String.valueOf(params.get("title"));
                Function<AgentTools.FindMyPostsRequest, String> func =
                        applicationContext.getBean("findMyPostsByTitle", Function.class);
                return func.apply(new AgentTools.FindMyPostsRequest(title, currentUserId));
            }

            // 🔥 [신규] 내 최근 게시글 조회
            case "getMyRecentPosts" -> {
                Function<String, String> func =
                        applicationContext.getBean("getMyRecentPosts", Function.class);
                return func.apply(currentUserId);
            }

            case "deletePostById" -> {
                Object rawId = params.get("postId");
                Integer postId;

                // 🔥 [핵심 수정] 숫자 타입 안전 처리 (호환되지 않는 타입 오류 해결)
                if (rawId instanceof Number) {
                    postId = ((Number) rawId).intValue();
                } else {
                    try {
                        postId = Integer.parseInt(String.valueOf(rawId));
                    } catch (NumberFormatException e) {
                        return "오류: 게시글 ID 형식이 잘못되었습니다.";
                    }
                }

                Object rawReqId = params.get("requesterId");
                String reqId = (rawReqId != null) ? String.valueOf(rawReqId) : currentUserId;

                Function<AgentTools.DeletePostByIdRequest, String> func =
                        applicationContext.getBean("deletePostById", Function.class);

                return func.apply(new AgentTools.DeletePostByIdRequest(postId, reqId));
            }

            case "updateAnalysisTitle" -> {
                Function<AgentTools.UpdateAnalysisTitleRequest, String> func =
                        applicationContext.getBean("updateAnalysisTitle", Function.class);
                return func.apply(new AgentTools.UpdateAnalysisTitleRequest(
                        String.valueOf(params.get("analysisId")),
                        String.valueOf(params.get("newTitle"))
                ));
            }

            case "getAnalysisHistory" -> {
                Function<String, String> func =
                        applicationContext.getBean("getAnalysisHistory", Function.class);
                return func.apply(currentUserId);
            }

            default -> {
                return "알 수 없는 도구 호출: " + toolName;
            }
        }
    }

    private void saveHistory(String id, List<Message> history) {
        if (history.size() > 20) {
            history = new ArrayList<>(history.subList(history.size() - 10, history.size()));
        }
        conversationStore.put(id, history);
    }
}