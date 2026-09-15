package app.accentury.backend.training;

/**
 * 학습 샘플 1건(WAV + 메타)을 보존하는 곳 (KAN-201).
 * <p>
 * 호출 시점은 분석 작업의 상태 전이가 끝난 <b>뒤</b>, 오디오 버퍼를 지우기 <b>전</b>이다
 * ({@code HttpAnalysisDispatcher}). 저장 실패가 분석 결과에 영향을 주면 안 되므로 구현은 예외를
 * 밖으로 내지 않는다 - 삼키고 WARN 로그와 지표로만 드러낸다. 호출부도 한 번 더 감싸서, 구현의
 * 실수로 예외가 새도 버퍼 파기(finally)와 종결이 흔들리지 않게 한다.
 * <p>
 * 버킷이 없는 배포(로컬, 테스트, prod)는 {@link #NONE}이다 - 빈이 없으면 소비자가 이 값을 쓴다.
 */
@FunctionalInterface
public interface TrainingSampleStore {

    /** 아무것도 저장하지 않는다 - {@code accentury.training.bucket}이 없는 배포의 자리. */
    TrainingSampleStore NONE = sample -> {
    };

    /**
     * 샘플을 보존한다. 오디오 버퍼는 호출이 돌아온 뒤 호출부가 지우므로 비동기로 붙들지 않는다.
     *
     * @param sample 저장할 샘플 - {@code audio}는 업로드 받은 WAV 바이트 그대로다.
     */
    void save(TrainingSample sample);
}
