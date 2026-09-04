/**
 * 테스트 콘텐츠의 발행, 활성 버전 관리, 조회 (KAN-10, KAN-26).
 * <p>
 * 불변 테스트 정의(문항 10개 = 음성 5 + 어휘 5)를 버전 경로로 제공하고(§3.2), 그중 무엇을
 * 활성으로 쓸지를 운영자가 바꾼다(§6). 발행 입력은 DB이며 정의는 마이그레이션의 INSERT로만
 * 들어온다 (2026-08-09 확정) - 애플리케이션에는 정의를 만들거나 지우는 경로가 없다.
 */
@NullMarked
package app.accentury.backend.testdefinition;

import org.jspecify.annotations.NullMarked;
