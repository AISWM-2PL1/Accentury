/**
 * 종합 점수 집계와 등급 판정 (KAN-21).
 * <p>
 * sv-0.3 확정 집계식(API 명세서 §4.3): 억양 점수와 단어 점수를 2:1 가중 평균해
 * 종합 점수를 만들고 5개 캐릭터형 등급으로 매핑한다. 가중치와 등급 경계는 코드가
 * 아니라 {@code score-versions/*.json} 설정 파일이 정본이다 - AI 오프라인
 * 재채점(KAN-47)이 같은 파일을 읽어 종합 점수를 재현할 수 있어야 한다.
 * <p>
 * 합산과 등급 판정은 {@code /complete}(KAN-16) 시점에 1회 수행되고,
 * 결과는 {@code /result}(KAN-25)가 반환한다 - 이 패키지는 그 둘이 소비하는 순수 계산이다.
 */
@NullMarked
package app.accentury.backend.scoring;

import org.jspecify.annotations.NullMarked;
