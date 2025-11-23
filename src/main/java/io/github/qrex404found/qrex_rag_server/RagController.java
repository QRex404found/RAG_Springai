package io.github.qrex404found.qrex_rag_server;



import com.fasterxml.jackson.databind.JsonNode;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;

import org.springframework.web.bind.annotation.GetMapping;

import org.springframework.web.bind.annotation.RequestParam;

import org.springframework.web.bind.annotation.RestController;

import org.springframework.web.client.RestClient;

import org.springframework.http.MediaType;



import java.util.List;

import java.util.Map;



@RestController

public class RagController {



    private final KnowledgeBaseService knowledgeBaseService;

    private final RestClient restClient;

    private final String apiKey;

    private final String modelName;

    private final ObjectMapper objectMapper;



    public RagController(KnowledgeBaseService knowledgeBaseService,

                         @Value("${custom.rag.api-key}") String apiKey,

// ⭐️ 설정 파일에서 RAG용 모델명을 가져옵니다.

                         @Value("${custom.rag.model}") String modelName) {

        this.knowledgeBaseService = knowledgeBaseService;

        this.apiKey = apiKey;

        this.modelName = modelName; // 변수에 저장

        this.restClient = RestClient.builder().build();

        this.objectMapper = new ObjectMapper();

    }



// ⭐️ FastAPI 포맷을 100% 유지한 프롬프트

    private final String promptTemplateText = """

당신은 최고의 QR코드 피싱(큐싱) 탐지 전문가입니다.

주어진 'URL'과 '추가 정보'가 피싱 위험이 있는지 아래의 '전체 지식 베이스'를 참조하여 분석하고, 최종 판단을 'JSON 형식'으로 내려주세요.



**중요: 지식 베이스에는 검증된 화이트리스트 도메인, 피싱 블랙리스트, 탐지 패턴이 포함되어 있습니다.**



[전체 지식 베이스]

%s



[분석 대상 URL 및 추가 정보]

URL: %s

IP 위치: %s

실시간 Safe Browsing 결과: %s



[분석 지침 - 반드시 순서대로 확인]

1. **블랙리스트 우선 확인**

- URL의 도메인이 블랙리스트에 있으면 → 즉시 DANGEROUS

2. **타이포스쿼팅 탐지** (가장 위험)

- 화이트리스트 도메인과 유사하지만 철자가 다르면 → 즉시 DANGEROUS

3. **URL 구조 위험 요소 확인**

- IP 주소 직접 사용, HTTP 사용, 복잡한 서브도메인 → 위험도 상승

4. **화이트리스트 정확 매칭**

- 화이트리스트 일치 시 → 기본 SAFE (단, URL 구조 위험 요소가 있다면 SUSPICIOUS로 하향 조정)

5. **추가 정보 종합 판단**


[최종 판단 강제 규칙] ⭐️ 이 부분을 추가하여 DANGEROUS 사용을 강제합니다.

1. 명확한 **타이포스쿼팅** 패턴이 발견되거나, **IP 직접 사용** 시 최종 risk_level은 **반드시 'DANGEROUS'**여야 합니다.

2. 화이트리스트 도메인이지만 **HTTP 사용** 또는 **의심스러운 경로**가 있다면 **'SUSPICIOUS'**여야 합니다.

3. 1, 2번 외 모든 경우는 'SAFE'입니다.



[출력 형식]

{{

"url": "분석한 URL",

"risk_level": "최종 판단 (반드시 'SAFE', 'SUSPICIOUS', 'DANGEROUS' 중 하나로만 응답)",

"reason": "위험도를 판단한 핵심 근거 (화이트리스트/블랙리스트 매칭 여부와 위험 패턴을 명확히 설명)",

"analysis_details": [

"화이트리스트/블랙리스트 매칭 결과 (정확한 도메인명 명시)",

"타이포스쿼팅 또는 URL 구조 분석 결과",

"IP 위치 및 Safe Browsing 결과 반영"

]

}}



**주의: reason과 analysis_details에는 반드시 구체적인 도메인명과 매칭 결과를 포함해야 합니다.**

""";



    @GetMapping("/api/rag")

    public String ragQuery(

            @RequestParam("question") String question,

            @RequestParam(value = "ip_location", defaultValue = "정보 없음") String ipLocation,

            @RequestParam(value = "safe_browsing_result", defaultValue = "미확인") String safeBrowsingResult) {



// 1. 블랙리스트/화이트리스트 선제 검사

// (여기서 반환하는 포맷도 FastAPI와 똑같이 맞췄습니다)

        if (knowledgeBaseService.isBlacklisted(question)) {

            return """

{

"url": "%s",

"risk_level": "DANGEROUS",

"reason": "블랙리스트에 등재된 악성 도메인입니다. (룰 기반 즉시 차단)",

"analysis_details": [

"RAG 호출 생략 (사전 룰 기반 차단)"

]

}

""".formatted(question);

        }



// 2. 프롬프트 완성

        String contextText = this.knowledgeBaseService.getKnowledgeAsText();

        String safeContextText = contextText.replace("%", "퍼센트");

        String finalPrompt = promptTemplateText.formatted(safeContextText, question, ipLocation, safeBrowsingResult);



// 3. Gemini 호출 (안정적인 2.5-flash 사용)

//String geminiUrl = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=" + apiKey;

        String geminiUrl = "https://generativelanguage.googleapis.com/v1beta/models/" + this.modelName + ":generateContent?key=" + apiKey;



        var requestBody = Map.of(

                "contents", List.of(Map.of("parts", List.of(Map.of("text", finalPrompt))))

        );



        try {

            String rawResponse = restClient.post()

                    .uri(geminiUrl)

                    .contentType(MediaType.APPLICATION_JSON)

                    .body(requestBody)

                    .retrieve()

                    .body(String.class);



// 구글 응답 껍데기를 벗겨서 깔끔한 JSON만 리턴

            return extractContent(rawResponse);



        } catch (Exception e) {

            e.printStackTrace();

// 에러 발생 시에도 포맷 유지

            return """

{

"url": "%s",

"risk_level": "ERROR",

"reason": "AI 서버 통신 중 오류 발생: %s",

"analysis_details": []

}

""".formatted(question, e.getMessage().replace("\"", "'"));

        }

    }



// JSON 추출 도우미 메서드

    private String extractContent(String rawJson) {

        try {

            JsonNode root = objectMapper.readTree(rawJson);

            String text = root.path("candidates").get(0)

                    .path("content").path("parts").get(0)

                    .path("text").asText();



// 마크다운 코드블럭 제거 (순수 JSON만 남김)

            return text.replace("```json", "").replace("```", "").trim();

        } catch (Exception e) {

            System.err.println("응답 파싱 실패: " + e.getMessage());

            return rawJson;

        }

    }

}