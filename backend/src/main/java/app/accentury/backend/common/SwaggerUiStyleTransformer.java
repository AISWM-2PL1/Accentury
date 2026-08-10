package app.accentury.backend.common;

import jakarta.servlet.http.HttpServletRequest;
import org.springdoc.core.properties.SwaggerUiConfigProperties;
import org.springdoc.core.properties.SwaggerUiOAuthProperties;
import org.springdoc.core.providers.ObjectMapperProvider;
import org.springdoc.webmvc.ui.SwaggerIndexPageTransformer;
import org.springdoc.webmvc.ui.SwaggerWelcomeCommon;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.resource.ResourceTransformerChain;
import org.springframework.web.servlet.resource.TransformedResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Swagger UI의 인라인 코드가 위아래 줄과 겹쳐 보이는 문제를 고친다.
 * <p>
 * Swagger UI 기본 CSS는 인라인 {@code <code>}에 {@code padding: 5px 7px}을 준다. 그런데 인라인 요소의
 * 세로 padding은 줄 상자(line box) 높이를 늘리지 않고 그냥 넘쳐흐른다. 그래서 코드 조각의 배경 상자가
 * 줄 간격보다 커지고, 문단이 두 줄 이상으로 접히면 아래 줄 글자를 덮는다. 파라미터와 응답 설명처럼
 * 칸이 좁은 곳은 반드시 접히므로 마크다운을 아무리 고쳐도 피할 수 없다.
 * <p>
 * 줄 간격을 늘리고 세로 padding을 줄여 상자가 줄 안에 들어오게 한다.
 * <p>
 * springdoc이 {@code indexPageTransformer}를 {@code @ConditionalOnMissingBean}으로 등록하므로
 * 이 빈이 있으면 그쪽이 물러난다. 기본 변환(OAuth 설정, CSRF, 문서 제목 등)은 상위 클래스가 그대로 한다.
 */
@Component
class SwaggerUiStyleTransformer extends SwaggerIndexPageTransformer {

    /**
     * 줄 간격 1.9와 세로 padding 2px 조합.
     * 코드 조각 높이는 대략 글자 17px + padding 4px = 21px이고 줄 상자는 14px * 1.9 = 26.6px이라
     * 상자가 줄 안에 들어온다. 기본값(padding 5px)은 27px이라 줄 간격을 넘어선다.
     */
    private static final String STYLE = """
            <style>
              .swagger-ui .markdown,
              .swagger-ui .renderedMarkdown { line-height: 1.9; }
              .swagger-ui .markdown code,
              .swagger-ui .renderedMarkdown code { padding: 2px 6px; }
            </style>
            """;

    SwaggerUiStyleTransformer(SwaggerUiConfigProperties swaggerUiConfig,
                              SwaggerUiOAuthProperties swaggerUiOAuthProperties,
                              SwaggerWelcomeCommon swaggerWelcomeCommon,
                              ObjectMapperProvider objectMapperProvider) {
        super(swaggerUiConfig, swaggerUiOAuthProperties, swaggerWelcomeCommon, objectMapperProvider);
    }

    @Override
    public Resource transform(HttpServletRequest request, Resource resource, ResourceTransformerChain chain)
            throws IOException {
        Resource transformed = super.transform(request, resource, chain);
        // index.html에만 손댄다 - js와 css까지 문자열로 읽으면 낭비다
        String uri = request.getRequestURI();
        if (uri == null || !uri.endsWith("index.html")) {
            return transformed;
        }
        String html = new String(transformed.getContentAsByteArray(), StandardCharsets.UTF_8);
        if (!html.contains("</head>")) {
            return transformed;    // Swagger UI 구조가 바뀐 경우 - 조용히 원본을 돌려준다
        }
        return new TransformedResource(resource,
                html.replace("</head>", STYLE + "</head>").getBytes(StandardCharsets.UTF_8));
    }
}
