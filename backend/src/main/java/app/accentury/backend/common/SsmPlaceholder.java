package app.accentury.backend.common;

/**
 * Terraform({@code infra/modules/config/main.tf})이 값 없는 SSM 파라미터를 만들 때 넣는 자리 표시 값.
 * <p>
 * 슬랙 Incoming Webhook URL이나 카카오 앱 Admin 키처럼 밖(슬랙 콘솔, 카카오디벨로퍼스 콘솔)이
 * 발급하는 값은 Terraform이 만들 수 없다. 그래서 자리만 만들고 운영자가 apply 뒤
 * {@code put-parameter}로 덮어쓰는데, 그 사이에 뜬 backend는 이 리터럴을 값으로 받는다 -
 * 레포를 읽은 누구나 아는 값이라 <b>어느 코드도 이것을 진짜 값으로 인정하면 안 된다</b>.
 * <p>
 * 리터럴이 여기 있는 것은 {@code feedback} 패키지가 {@code share} 패키지를 import하지 않게 하려는
 * 것이다 (KAN-211). 처음 쓴 곳은 {@code KakaoWebhookAuth}(KAN-164)였고 그 상수는 지금 이 값을
 * 가리킨다 - 두 곳이 같은 문자열이어야 Terraform 쪽 대조 테스트({@code SsmEnvironmentBindingTest})가
 * 두 자원을 한 리터럴로 볼 수 있다.
 */
public final class SsmPlaceholder {

    /**
     * 값을 아직 넣지 않은 SSM 파라미터의 내용. Terraform의 {@code value_wo}와 글자 그대로 같아야 하고,
     * 같은지는 {@code SsmEnvironmentBindingTest}가 {@code main.tf}를 읽어 대조한다.
     */
    public static final String UNSET = "unset-put-parameter-after-apply";

    private SsmPlaceholder() {
    }
}
