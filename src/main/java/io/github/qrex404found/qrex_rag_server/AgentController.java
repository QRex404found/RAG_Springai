package io.github.qrex404found.qrex_rag_server;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.context.ApplicationContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
public class AgentController {

    private final ChatClient chatClient;
    private final ApplicationContext applicationContext;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 번호 선택 삭제용 임시 저장소 (대화별)
    private final Map<String, List<Map<String, Object>>> foundPosts = new ConcurrentHashMap<>();
    // 간단한 대화 히스토리 (스몰톡용)
    private final Map<String, List<Message>> convStore = new ConcurrentHashMap<>();

    public AgentController(ChatClient chatClient, ApplicationContext applicationContext) {
        this.chatClient = chatClient;
        this.applicationContext = applicationContext;
        System.out.println("✅ AgentController Loaded (Stable Agent v1 + N-th Delete)");
    }

    // ---------- 인텐트 타입 & 결과 ----------

    private enum IntentType {
        CREATE_POST,          // 게시글 작성
        DELETE_POST,          // 게시글 삭제 (최근/오래된/제목/N번째)
        RENAME_ANALYSIS_TITLE,// QR 분석 기록 제목 변경
        VIEW_RECENT_POSTS,    // 내가 쓴 최근 게시글 목록 보기
        SMALL_TALK,           // 간단한 대화
        UNKNOWN               // 분류 실패/해당 없음
    }

    private record IntentResult(
            IntentType intent,
            String title,       // CREATE_POST / DELETE_POST(BY_TITLE)
            String content,     // CREATE_POST
            String newTitle,    // RENAME_ANALYSIS_TITLE
            String deleteMode   // DELETE_POST: LATEST / OLDEST / BY_TITLE / INDEX
    ) {}

    // ---------- 메인 엔드포인트 ----------

    @GetMapping("/api/agent/chat")
    public String chat(
            @RequestParam("message") String message,
            @RequestParam(value = "isLoggedIn", defaultValue = "false") boolean isLoggedIn,
            @RequestParam(value = "userId", required = false) String userId,
            @RequestParam(value = "conversationId", defaultValue = "default") String convId
    ) {

        System.out.println("💬 [Agent] message=" + message + ", userId=" + userId + ", convId=" + convId);

        boolean logged = isLoggedIn || (userId != null && !"null".equals(userId));
        String user = extractUserId(userId);

        // 0. QR 정밀 분석 요청은 RAG 엔드포인트로 유도
        if (containsAny(message, "qr분석", "url분석", "분석해줘", "QR 분석")) {
            return "채팅창에서는 정밀 분석을 지원하지 않습니다.\n상단의 [QR 분석 페이지]에서 분석을 진행해주세요. [[GO_TO_ANALYSIS]]";
        }

        // 0-1. 비밀번호 관련 요청은 바로 차단
        if (containsAny(message, "비밀번호", "암호", "패스워드")) {
            return "죄송합니다. 현재 QREX 챗봇은 비밀번호 변경 기능을 직접 지원하지 않습니다.\n서비스 내 설정/보안 메뉴에서 비밀번호를 변경해주세요.";
        }

        // 1. 이전에 “여러 개의 게시글이 발견되었습니다.” 를 띄워놓은 상태에서
        //    "1번", "2번" 같은 번호가 들어왔으면 → 번호 기반 삭제 처리
        if (logged && message.matches("^[0-9]+번?.*") && foundPosts.containsKey(convId)) {
            return handleDeleteByIndex(message, user, convId);
        }

        // 2. 인텐트 분류 (LLM)
        IntentResult ir = classifyIntent(message);
        System.out.println("🎯 [Intent] " + ir);

        return switch (ir.intent()) {
            case CREATE_POST -> {
                if (!logged) {
                    yield "로그인 후 커뮤니티에 글을 작성할 수 있습니다.";
                }
                yield handleCreatePost(ir, user);
            }
            case DELETE_POST -> {
                if (!logged) {
                    yield "로그인 후 게시글을 삭제할 수 있습니다.";
                }
                // 🔥 원본 message도 함께 넘겨서 "3번째" 같은 표현 파싱
                yield handleDeletePost(ir, user, convId, message);
            }
            case RENAME_ANALYSIS_TITLE -> {
                if (!logged) {
                    yield "로그인 후 분석 기록 제목을 변경할 수 있습니다.";
                }
                yield handleRenameAnalysisTitle(ir, user, message);
            }
            case VIEW_RECENT_POSTS -> {
                if (!logged) {
                    yield "로그인 후 내 게시글 목록을 조회할 수 있습니다.";
                }
                yield handleViewMyPosts(user);
            }
            case SMALL_TALK -> {
                if (!isAllowed(message)) {
                    yield "죄송합니다. QREX 보안 및 QR 이용 관련 질문 또는 커뮤니티 기능과 관련된 내용만 답변할 수 있습니다.";
                }
                yield handleSmallTalk(message, convId);
            }
            case UNKNOWN -> {
                // 여기까지 오면 "의도 모름" 케이스
                yield "죄송합니다. QREX 보안 및 QR 이용 관련 질문 또는 커뮤니티 기능과 관련된 내용만 답변할 수 있습니다.";
            }
        };
    }

    // ---------- 인텐트 분류 (LLM) ----------

    private IntentResult classifyIntent(String message) {
        try {
            String resp = chatClient.prompt()
                    .system("""
                        당신은 QRex 챗봇의 '의도 분류기'입니다.

                        사용자의 한 문장을 아래 intent 중 하나로 분류하고,
                        필요한 경우 title, content, newTitle, mode를 설정하세요.

                        intent 종류:
                        - CREATE_POST: 커뮤니티 새 글 작성
                        - DELETE_POST: 게시글 삭제
                        - RENAME_ANALYSIS_TITLE: 분석 기록 제목 변경
                        - VIEW_RECENT_POSTS: 내가 쓴 글 목록 보여줘
                        - SMALL_TALK: 인사/짧은 대화
                        - UNKNOWN: 위에 해당하지 않음

                        DELETE_POST일 때의 mode:
                        - "LATEST": "최근 글 삭제", "방금 쓴 글 지워줘" 등
                        - "OLDEST": "가장 오래된 글 삭제", "맨 마지막 글 지워줘" 등
                        - "BY_TITLE": "피싱 관련 글 삭제해줘"처럼 제목 일부가 포함된 경우
                        - "INDEX": "3번째 글 삭제", "2번 게시글 삭제"처럼 N번째를 언급한 경우

                        출력은 반드시 아래 JSON 형식 중 하나로만 합니다.
                        코드블록(````), 설명 문장, 기타 텍스트는 절대 포함하지 마세요.

                        예시:
                        {"intent": "DELETE_POST", "mode": "LATEST"}
                        {"intent": "DELETE_POST", "mode": "BY_TITLE", "title": "피싱 주의 공지"}
                        {"intent": "DELETE_POST", "mode": "INDEX"}
                        {"intent": "CREATE_POST", "title": "제목", "content": "내용"}
                        {"intent": "RENAME_ANALYSIS_TITLE", "newTitle": "카카오 피싱 분석"}
                        {"intent": "VIEW_RECENT_POSTS"}
                        {"intent": "SMALL_TALK"}
                        """)
                    .user(message)
                    .call()
                    .content();

            String json = extractJson(resp);
            Map<String, Object> map = objectMapper.readValue(json, new TypeReference<>() {});

            String intentStr = String.valueOf(map.getOrDefault("intent", "UNKNOWN"));
            IntentType intent;
            try {
                intent = IntentType.valueOf(intentStr);
            } catch (Exception e) {
                intent = IntentType.UNKNOWN;
            }

            String title      = map.get("title")     != null ? String.valueOf(map.get("title"))     : null;
            String content    = map.get("content")   != null ? String.valueOf(map.get("content"))   : null;
            String newTitle   = map.get("newTitle")  != null ? String.valueOf(map.get("newTitle"))  : null;
            String deleteMode = map.get("mode")      != null ? String.valueOf(map.get("mode"))      : null;

            return new IntentResult(
                    intent,
                    clean(title),
                    clean(content),
                    clean(newTitle),
                    deleteMode != null ? deleteMode.toUpperCase() : null
            );

        } catch (Exception e) {
            System.err.println("❌ Intent 분류 실패: " + e.getMessage());
            return new IntentResult(IntentType.UNKNOWN, null, null, null, null);
        }
    }

    // ---------- 핸들러들 ----------

    /** 게시글 작성 */
    private String handleCreatePost(IntentResult ir, String userId) {
        String title = ir.title();
        String content = ir.content();

        if (title == null || title.isBlank()) {
            return "작성할 게시글의 제목을 알려주세요.";
        }
        if (content == null || content.isBlank()) {
            return "게시글 내용도 함께 알려주세요.";
        }

        AgentTools.PostRequest req = new AgentTools.PostRequest(title, content, null, userId);

        try {
            @SuppressWarnings("unchecked")
            Function<AgentTools.PostRequest, String> createTool =
                    (Function<AgentTools.PostRequest, String>) applicationContext.getBean("createCommunityPost");

            String result = createTool.apply(req);

            System.out.println("📝 [CREATE_POST] result=" + result);

            // 백엔드 응답 형식에 따라 단순 성공/실패만 판단
            if (result != null && (result.contains("성공") || result.contains("저장") || result.contains("title"))) {
                return "커뮤니티에 '" + title + "' 제목으로 게시글을 작성했습니다.";
            }
            return "게시글 작성 중 오류가 발생했습니다. (결과: " + result + ")";

        } catch (Exception e) {
            e.printStackTrace();
            return "게시글 작성 중 시스템 오류가 발생했습니다. (오류: " + e.getMessage() + ")";
        }
    }

    /** 게시글 삭제 (최근/오래된/제목 기반/N번째) */
    private String handleDeletePost(IntentResult ir, String userId, String convId, String originalMessage) {
        String mode = ir.deleteMode();
        String title = ir.title();

        System.out.println("🗑 [DELETE_POST] mode=" + mode + ", title=" + title + ", msg=" + originalMessage);

        // 1) 가장 최근 게시글
        if ("LATEST".equalsIgnoreCase(mode)) {
            return handleDeleteMostRecentPost(userId);
        }

        // 2) 가장 오래된 게시글
        if ("OLDEST".equalsIgnoreCase(mode)) {
            return handleDeleteOldestPost(userId);
        }

        // 3) N번째 게시글 삭제 (예: "3번째 글 삭제해줘", "2번 게시글 지워줘")
        // - LLM이 mode를 "INDEX"로 주거나, mode가 null인데 문장에 숫자 표현이 있는 경우
        boolean hasNthExpr = hasNthExpression(originalMessage);
        if ("INDEX".equalsIgnoreCase(mode) || (mode == null && hasNthExpr)) {
            return handleDeleteNthPost(originalMessage, userId);
        }

        // 4) 그 외 → 제목 기반 삭제 시도 (BY_TITLE)
        if (title == null || title.isBlank()) {
            // 여기까지 왔는데 제목도 없고, N번째 표현도 없으면 정보 부족
            return "삭제할 게시글의 제목이나 순서를 조금 더 구체적으로 알려주세요.\n예: '최근 게시글 삭제', '3번째 글 삭제', '카카오 피싱 글 삭제'";
        }

        return handleDeletePostByTitle(title, userId, convId);
    }

    /** 가장 최근 게시글 삭제 */
    private String handleDeleteMostRecentPost(String userId) {
        try {
            @SuppressWarnings("unchecked")
            Function<String, String> tool =
                    (Function<String, String>) applicationContext.getBean("getMyRecentPosts");

            String json = tool.apply(userId);
            List<Map<String, Object>> posts =
                    objectMapper.readValue(json, new TypeReference<>() {});

            if (posts.isEmpty()) {
                return "삭제할 최근 게시글이 없습니다.";
            }

            Map<String, Object> post = posts.get(0); // 최신 글
            Integer postId = parsePostId(post);

            if (postId == null) {
                return "최근 게시글의 ID를 찾지 못했습니다.";
            }

            AgentTools.DeletePostByIdRequest req =
                    new AgentTools.DeletePostByIdRequest(postId, userId);

            @SuppressWarnings("unchecked")
            Function<AgentTools.DeletePostByIdRequest, String> delTool =
                    (Function<AgentTools.DeletePostByIdRequest, String>) applicationContext.getBean("deletePostById");

            String result = delTool.apply(req);
            System.out.println("🗑 [DELETE_LATEST] result=" + result);

            return "가장 최근 게시글을 삭제했습니다.";

        } catch (Exception e) {
            e.printStackTrace();
            return "최근 게시글 삭제 중 오류가 발생했습니다. (오류: " + e.getMessage() + ")";
        }
    }

    /** 가장 오래된 게시글 삭제 */
    private String handleDeleteOldestPost(String userId) {
        try {
            @SuppressWarnings("unchecked")
            Function<String, String> tool =
                    (Function<String, String>) applicationContext.getBean("getMyRecentPosts");

            String json = tool.apply(userId);
            List<Map<String, Object>> posts =
                    objectMapper.readValue(json, new TypeReference<>() {});

            if (posts.isEmpty()) {
                return "삭제할 오래된 게시글이 없습니다.";
            }

            Map<String, Object> post = posts.get(posts.size() - 1); // 가장 오래된 글
            Integer postId = parsePostId(post);

            if (postId == null) {
                return "오래된 게시글의 ID를 찾지 못했습니다.";
            }

            AgentTools.DeletePostByIdRequest req =
                    new AgentTools.DeletePostByIdRequest(postId, userId);

            @SuppressWarnings("unchecked")
            Function<AgentTools.DeletePostByIdRequest, String> delTool =
                    (Function<AgentTools.DeletePostByIdRequest, String>) applicationContext.getBean("deletePostById");

            String result = delTool.apply(req);
            System.out.println("🗑 [DELETE_OLDEST] result=" + result);

            return "가장 오래된 게시글을 삭제했습니다.";

        } catch (Exception e) {
            e.printStackTrace();
            return "오래된 게시글 삭제 중 오류가 발생했습니다. (오류: " + e.getMessage() + ")";
        }
    }

    /** "3번째 글 삭제", "2번 게시글 삭제" 같은 요청 처리 */
    private String handleDeleteNthPost(String message, String userId) {
        try {
            @SuppressWarnings("unchecked")
            Function<String, String> tool =
                    (Function<String, String>) applicationContext.getBean("getMyRecentPosts");

            String json = tool.apply(userId);
            List<Map<String, Object>> posts =
                    objectMapper.readValue(json, new TypeReference<>() {});

            if (posts.isEmpty()) {
                return "삭제할 게시글이 없습니다.";
            }

            // 🔍 N번째 인덱스 계산 (0-based)
            int index = resolveNthIndexFromMessage(message, posts.size());
            if (index < 0 || index >= posts.size()) {
                return "요청하신 순서의 게시글을 찾을 수 없습니다. (1 ~ " + posts.size() + " 범위 안에서 말씀해주세요)";
            }

            Map<String, Object> target = posts.get(index);
            Integer postId = parsePostId(target);

            if (postId == null) {
                return "삭제할 게시글의 ID를 찾지 못했습니다.";
            }

            AgentTools.DeletePostByIdRequest req =
                    new AgentTools.DeletePostByIdRequest(postId, userId);

            @SuppressWarnings("unchecked")
            Function<AgentTools.DeletePostByIdRequest, String> delTool =
                    (Function<AgentTools.DeletePostByIdRequest, String>) applicationContext.getBean("deletePostById");

            String result = delTool.apply(req);
            System.out.println("🗑 [DELETE_NTH] idx=" + index + ", result=" + result);

            return (index + 1) + "번째 게시글을 삭제했습니다.";

        } catch (Exception e) {
            e.printStackTrace();
            return "N번째 게시글 삭제 중 시스템 오류가 발생했습니다. (오류: " + e.getMessage() + ")";
        }
    }

    /** 제목 기반 삭제 (여러 개면 번호 선택 모드 진입) */
    private String handleDeletePostByTitle(String extractedTitle, String userId, String convId) {
        try {
            @SuppressWarnings("unchecked")
            Function<String, String> tool =
                    (Function<String, String>) applicationContext.getBean("getMyRecentPosts");

            String json = tool.apply(userId);
            String preview = json.substring(0, Math.min(json.length(), 200));
            System.out.println("🗑 [DELETE_BY_TITLE] raw json preview=" + preview + (json.length() > 200 ? "..." : ""));

            List<Map<String, Object>> posts =
                    objectMapper.readValue(json, new TypeReference<>() {});
            System.out.println("🗑 [DELETE_BY_TITLE] total posts=" + posts.size());

            List<Map<String, Object>> matched = posts.stream()
                    .filter(p -> {
                        String dbTitle = null;
                        if (p.get("title") != null && !"null".equals(String.valueOf(p.get("title")))) {
                            dbTitle = String.valueOf(p.get("title"));
                        } else if (p.get("postTitle") != null) {
                            dbTitle = String.valueOf(p.get("postTitle"));
                        } else if (p.get("PostTitle") != null) {
                            dbTitle = String.valueOf(p.get("PostTitle"));
                        }
                        return dbTitle != null && dbTitle.contains(extractedTitle);
                    })
                    .toList();

            System.out.println("🗑 [DELETE_BY_TITLE] matched size=" + matched.size());

            if (matched.isEmpty()) {
                return "해당 제목을 포함한 게시글을 찾을 수 없습니다.";
            }

            if (matched.size() == 1) {
                Map<String, Object> post = matched.get(0);
                Integer postId = parsePostId(post);

                if (postId == null) {
                    return "삭제할 게시글의 ID를 찾지 못했습니다.";
                }

                AgentTools.DeletePostByIdRequest req =
                        new AgentTools.DeletePostByIdRequest(postId, userId);

                @SuppressWarnings("unchecked")
                Function<AgentTools.DeletePostByIdRequest, String> delTool =
                        (Function<AgentTools.DeletePostByIdRequest, String>) applicationContext.getBean("deletePostById");

                String result = delTool.apply(req);
                System.out.println("🗑 [DELETE_BY_TITLE] single result=" + result);

                return "게시글이 정상적으로 삭제되었습니다.";
            }

            // 여러 개 → 번호 선택 모드
            foundPosts.put(convId, matched);

            StringBuilder sb = new StringBuilder("여러 개의 게시글이 발견되었습니다.\n삭제할 번호를 선택해주세요.\n\n");
            for (int i = 0; i < matched.size(); i++) {
                Map<String, Object> post = matched.get(i);
                Object t1 = post.get("title");
                Object t2 = post.get("postTitle");
                Object t3 = post.get("PostTitle");
                Object contentPreview = post.get("contentPreview");

                String displayTitle = t1 != null ? t1.toString()
                        : t2 != null ? t2.toString()
                        : t3 != null ? t3.toString()
                        : "[제목 없음]";

                String displayContent = contentPreview != null ? contentPreview.toString() : "[내용 없음]";

                sb.append(i + 1)
                        .append(") 제목: ").append(displayTitle)
                        .append(" / 내용: ").append(displayContent)
                        .append("\n");
            }
            return sb.toString();

        } catch (Exception e) {
            e.printStackTrace();
            return "게시글 삭제 중 오류가 발생했습니다. (오류: " + e.getMessage() + ")";
        }
    }

    /** 번호로 게시글 선택 후 삭제 (1번, 2번…) */
    private String handleDeleteByIndex(String message, String userId, String convId) {
        List<Map<String, Object>> list = foundPosts.get(convId);
        if (list == null || list.isEmpty()) {
            return "삭제할 게시글 후보 목록이 없습니다. 다시 삭제 요청을 시도해주세요.";
        }

        try {
            int num = Integer.parseInt(message.replaceAll("[^0-9]", ""));
            if (num < 1 || num > list.size()) {
                return "올바른 번호를 선택해주세요. (1 ~ " + list.size() + ")";
            }

            Map<String, Object> post = list.get(num - 1);
            Integer postId = parsePostId(post);

            if (postId == null) {
                return "삭제할 게시글의 ID를 찾지 못했습니다.";
            }

            AgentTools.DeletePostByIdRequest req =
                    new AgentTools.DeletePostByIdRequest(postId, userId);

            @SuppressWarnings("unchecked")
            Function<AgentTools.DeletePostByIdRequest, String> delTool =
                    (Function<AgentTools.DeletePostByIdRequest, String>) applicationContext.getBean("deletePostById");

            String result = delTool.apply(req);
            System.out.println("🗑 [DELETE_BY_INDEX] result=" + result);

            // 한 번 사용했으니 캐시 제거
            foundPosts.remove(convId);

            return "선택하신 번호의 게시글을 삭제했습니다.";

        } catch (Exception e) {
            e.printStackTrace();
            return "게시글 삭제 중 오류가 발생했습니다. (오류: " + e.getMessage() + ")";
        }
    }

    /** 내 최근 게시글 목록 보기 */
    private String handleViewMyPosts(String userId) {
        try {
            @SuppressWarnings("unchecked")
            Function<String, String> tool =
                    (Function<String, String>) applicationContext.getBean("getMyRecentPosts");

            String json = tool.apply(userId);
            // 일단 그대로 반환 (프론트에서 파싱해서 쓰기)
            return json;

        } catch (Exception e) {
            e.printStackTrace();
            return "내 게시글 목록을 조회하는 중 오류가 발생했습니다. (오류: " + e.getMessage() + ")";
        }
    }

    /** 분석 기록 제목 변경 (가장 최근/오래된/N번째는 message 내용으로 판단) */
    private String handleRenameAnalysisTitle(IntentResult ir, String userId, String originalMessage) {
        String newTitle = ir.newTitle();
        if (newTitle == null || newTitle.isBlank()) {
            return "변경할 새 제목을 명확하게 알려주세요.";
        }

        try {
            @SuppressWarnings("unchecked")
            Function<String, String> historyTool =
                    (Function<String, String>) applicationContext.getBean("getAnalysisHistory");

            String historyJson = historyTool.apply(userId);
            System.out.println("🧾 [ANALYSIS_HISTORY] raw=" + historyJson);

            // 백엔드가 Page 형태로 반환한다고 가정 (content 배열)
            List<Map<String, Object>> historyList;

            if (historyJson.trim().startsWith("{")) {
                Map<String, Object> root = objectMapper.readValue(historyJson, new TypeReference<>() {});
                Object content = root.get("content");
                if (content instanceof List) {
                    //noinspection unchecked
                    historyList = (List<Map<String, Object>>) content;
                } else {
                    historyList = Collections.emptyList();
                }
            } else {
                historyList = objectMapper.readValue(historyJson, new TypeReference<>() {});
            }

            if (historyList.isEmpty()) {
                return "변경할 분석 기록을 찾을 수 없습니다.";
            }

            // 최신순 정렬 (analysisId 기준, 없으면 그대로)
            historyList.sort((a, b) -> Long.compare(
                    parseLongField(b, "analysisId"),
                    parseLongField(a, "analysisId")
            ));

            int index = decideIndexFromMessage(originalMessage, historyList.size());
            Map<String, Object> target = historyList.get(index);

            String analysisId = parseStringField(target,
                    "analysisId", "id", "analysisRecordId");

            if (analysisId == null || analysisId.isBlank()) {
                return "분석 기록 ID를 찾지 못했습니다.";
            }

            AgentTools.UpdateAnalysisTitleRequest req =
                    new AgentTools.UpdateAnalysisTitleRequest(analysisId, newTitle, userId);

            @SuppressWarnings("unchecked")
            Function<AgentTools.UpdateAnalysisTitleRequest, String> updateTool =
                    (Function<AgentTools.UpdateAnalysisTitleRequest, String>) applicationContext.getBean("updateAnalysisTitle");

            String result = updateTool.apply(req);
            System.out.println("✏ [RENAME_ANALYSIS] result=" + result);

            if (result != null && result.contains("성공")) {
                return "해당 분석 기록의 제목을 '" + newTitle + "'(으)로 변경했습니다.";
            }
            return "제목 변경 중 오류가 발생했습니다. (결과: " + result + ")";

        } catch (Exception e) {
            e.printStackTrace();
            return "분석 기록 제목 변경 중 시스템 오류가 발생했습니다. (오류: " + e.getMessage() + ")";
        }
    }

    /** 간단한 스몰톡 / 일반 답변 */
    private String handleSmallTalk(String message, String convId) {
        List<Message> history = convStore.getOrDefault(convId, new ArrayList<>());
        List<Message> msgs = new ArrayList<>(history);
        msgs.add(new UserMessage(message));

        String reply = chatClient.prompt()
                .system("""
                    당신은 QREX 보안/QR 도우미입니다.
                    - QR 피싱, URL 안전성, 서비스 이용법과 관련된 가벼운 대화에 친절히 답합니다.
                    - 너무 개인적인 고민 상담이나 QREX와 무관한 잡담은 부드럽게 화제를 돌리세요.
                    """)
                .messages(msgs)
                .call()
                .content();

        history.add(new UserMessage(message));
        history.add(new AssistantMessage(reply));
        convStore.put(convId, history);

        return reply;
    }

    // ---------- 유틸리티 메서드들 ----------

    private String extractUserId(String userId) {
        if (userId != null && !userId.isBlank() && !"null".equals(userId)) {
            return userId;
        }
        return "guest";
    }

    private boolean isAllowed(String msg) {
        msg = msg.toLowerCase();
        return containsAny(msg,
                "큐싱", "피싱", "스미싱",
                "qr", "url",
                "로그인", "회원가입",
                "게시글", "삭제", "작성", "목록",
                "분석", "제목",
                "안녕", "안녕하세요", "하이"
        );
    }

    private boolean containsAny(String text, String... keywords) {
        if (text == null) return false;
        for (String k : keywords) {
            if (text.contains(k)) return true;
        }
        return false;
    }

    /** LLM 응답에서 JSON 부분만 추출 */
    private String extractJson(String jsonResponse) {
        if (jsonResponse == null) return "{}";

        int start = jsonResponse.indexOf('{');
        int end = jsonResponse.lastIndexOf('}');

        if (start != -1 && end != -1 && end > start) {
            return jsonResponse.substring(start, end + 1);
        }
        return "{}";
    }

    /** 조사 하나 정도 정리 */
    private String clean(String s) {
        if (s == null) return null;
        return s.replaceAll("(이|가|은|는)$", "").trim();
    }

    /** 게시글 ID 추출 (boardId / id / postId 대응) */
    private Integer parsePostId(Map<String, Object> map) {
        if (map == null) return null;
        Object val = map.get("boardId");
        if (val == null) val = map.get("id");
        if (val == null) val = map.get("postId");
        if (val == null) return null;
        try {
            return Integer.valueOf(String.valueOf(val));
        } catch (Exception e) {
            return null;
        }
    }

    private long parseLongField(Map<String, Object> map, String key) {
        if (map == null || map.get(key) == null) return 0L;
        try {
            return Long.parseLong(String.valueOf(map.get(key)));
        } catch (Exception e) {
            return 0L;
        }
    }

    private String parseStringField(Map<String, Object> map, String... keys) {
        if (map == null) return null;
        for (String k : keys) {
            Object v = map.get(k);
            if (v != null && !"null".equals(String.valueOf(v))) {
                return String.valueOf(v);
            }
        }
        return null;
    }

    /** "최근/오래된/N번째"에서 N 결정 (분석 기록용, 기본: 0 = 가장 최근) */
    private int decideIndexFromMessage(String msg, int size) {
        if (size <= 0) return 0;
        if (msg == null) return 0;

        if (msg.contains("오래된") || msg.contains("마지막")) {
            return size - 1;
        }

        Pattern p = Pattern.compile("(\\d+)(?:번째|번)");
        Matcher m = p.matcher(msg);
        if (m.find()) {
            try {
                int n = Integer.parseInt(m.group(1));
                if (n >= 1 && n <= size) {
                    return n - 1;
                }
            } catch (NumberFormatException ignored) {}
        }

        // 기본값: 가장 최근(0번)
        return 0;
    }

    /** "3번째", "2번" 같은 표현이 들어있는지 여부 */
    private boolean hasNthExpression(String msg) {
        if (msg == null) return false;
        Matcher m = Pattern.compile("(\\d+)(?:번째|번)").matcher(msg.replaceAll("\\s+", ""));
        return m.find();
    }

    /** "3번째 글", "2번 게시글" → 0-based index로 변환 (삭제용, 없으면 -1) */
    private int resolveNthIndexFromMessage(String msg, int size) {
        if (msg == null || size <= 0) return -1;

        Matcher m = Pattern.compile("(\\d+)(?:번째|번)").matcher(msg.replaceAll("\\s+", ""));
        if (m.find()) {
            try {
                int n = Integer.parseInt(m.group(1));
                int idx = n - 1;
                if (idx >= 0 && idx < size) {
                    return idx;
                }
            } catch (NumberFormatException ignored) {}
        }

        return -1;
    }
}
