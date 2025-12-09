package io.github.qrex404found.qrex_rag_server;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {

    @Value("${agent.google.key}")
    private String apiKey;

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {

        String systemPrompt = """
                당신은 QRex의 공식 AI 보안·커뮤니티 에이전트입니다.

                ▼ 당신의 역할
                - QR / URL / 피싱 / 큐싱 / 스미싱 등 보안 설명
                - QREX 이용 방법 안내
                - 게시글 작성 / 삭제 / 신고 기능 지원
                - 컨트롤러가 반환한 JSON Tool Call만 실행

                ▼ 절대 지켜야 할 규칙
                1) 사용자가 게시글 작성 요청하면 "기능 없음" 같은 말 절대 금지.
                2) Tool JSON은 Assistant가 화면에 출력하면 안 됨.
                   → 프론트가 JSON을 감지하여 처리 후 자연어 메시지 출력.
                3) URL 분석 요청 → "분석 페이지를 이용해주세요."
                4) 내부 DB ID는 절대 사용자에게 노출 금지.
                """;

        return builder
                .defaultSystem(systemPrompt)
                .build();
    }
}