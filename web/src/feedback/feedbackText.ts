/**
 * 이용 후기 시트의 문구 (KAN-211). `ads/adConsentText.ts`처럼 상수로 뺀다 — 화면과 테스트가
 * 같은 값을 보게 하고, 문구를 고치는 사람이 JSX를 뒤지지 않게 하기 위해서다.
 *
 * ## 검증 문구도 여기 있다
 *
 * 입력 규칙을 어겼을 때의 안내([FEEDBACK_INVALID_BODY_EMPTY] 아래)까지 이 파일이 든다. 화면에
 * 그려지는 말이라는 점에서 제목·버튼 라벨과 다를 것이 없고, 같은 규칙을 말하는 문장이 전송
 * 모듈과 시트에 따로 있으면 한쪽만 고쳐진다 — 실제로 갈리는 자리다: 검증은 `sendFeedback`이
 * 소유하고 그 결과를 그리는 것은 시트다.
 *
 * ## 사과하지 않고 캐묻지 않는다
 *
 * 말투는 앱의 다른 카피와 같다 (ux-ui.md 비난 없는 카피, "~해요"체). 후기를 받는 화면이라
 * 특히 걸리는 것이 [FEEDBACK_GUIDE]다 — "불편한 점을 알려 주세요"라고만 물으면 불만이 없는
 * 사람은 적을 말이 없어지고, 우리가 듣고 싶은 것은 불만만이 아니다.
 */

/**
 * 시트 제목. 아래 [FEEDBACK_OPEN]과 같은 말인 것은 의도다 — 버튼은 자기가 여는 것의 이름을
 * 그대로 적는 편이 무엇이 열릴지 분명하다. 그래도 상수를 둘로 나눠 둔다: 한쪽 말을 고칠 때
 * 다른 쪽이 조용히 따라 바뀌면 안 되는, 서로 다른 자리의 문구다.
 */
export const FEEDBACK_TITLE = '개발팀에 후기 보내기'

/** 결과 화면 하단의 진입 버튼 라벨 ([FEEDBACK_TITLE] 참고) */
export const FEEDBACK_OPEN = '개발팀에 후기 보내기'

/** 보낸 뒤 진입 버튼 자리에 남는 한 줄. 같은 결과에 두 번 보낼 길이 없다는 사실도 여기서 말한다 */
export const FEEDBACK_DONE_CAPTION = '후기를 보냈어요. 고마워요!'

/**
 * 무엇을 적으면 되는지. 본문 입력칸의 이름표이기도 하다 (`FeedbackSheet`의 `aria-labelledby`) —
 * 입력칸 위 안내가 곧 그 칸이 무엇인지 말하고 있어, 이름표를 따로 적으면 같은 말이 두 번 난다.
 */
export const FEEDBACK_GUIDE = '테스트는 어땠나요? 아쉬운 점, 이상했던 점, 바라는 점 무엇이든 적어 주세요.'

export const FEEDBACK_RATING_LABEL = '별점(선택)'
export const FEEDBACK_BODY_PLACEHOLDER = '500자까지 적을 수 있어요'
export const FEEDBACK_EMAIL_LABEL = '답변 받을 이메일(선택)'
export const FEEDBACK_EMAIL_HINT = '답변을 원하시면 적어 주세요. 후기 확인에만 쓰고 다른 데 쓰지 않아요.'

/** 방침 링크 앞뒤의 말. 링크 자체는 `legal/PrivacyPolicyLink`가 그린다 (adConsentText와 같은 규칙) */
export const FEEDBACK_DETAIL_LEAD = '자세한 내용은'
export const FEEDBACK_DETAIL_TAIL = '을 봐 주세요.'

export const FEEDBACK_SUBMIT = '보내기'
export const FEEDBACK_SENDING = '보내는 중…'
export const FEEDBACK_CLOSE = '닫기'
export const FEEDBACK_RETRY = '다시 보내기'

export const FEEDBACK_DONE_TITLE = '고마워요, 잘 받았어요'
export const FEEDBACK_DONE_BODY = '개발팀이 꼼꼼히 읽어볼게요.'

/**
 * 같은 결과에 이미 후기가 있다 (409 `FEEDBACK_ALREADY_SUBMITTED`).
 *
 * 실패로 적지 않는다 — 새로고침 뒤 다시 보낸 경우가 이 자리이고, 사용자가 하려던 일(후기가
 * 개발팀에 닿는 것)은 이미 이루어져 있다. "오류"라고 말하면 되지 않은 일로 읽힌다.
 */
export const FEEDBACK_ALREADY = '이 결과에는 이미 후기를 보냈어요.'

/** 세션이 만료됐다 (401·403). 되돌릴 길이 없으므로 다음 기회를 알린다 */
export const FEEDBACK_EXPIRED = '세션이 만료돼 후기를 보낼 수 없어요. 다시 테스트하면 새 결과에 남길 수 있어요.'

/** 네트워크가 끊겨 요청이 서버에 닿았는지조차 모르는 상태 */
export const FEEDBACK_NETWORK_ERROR = '네트워크 오류로 후기를 보내지 못했어요'

/**
 * 앱이 세션 값을 못 넘긴 상태. 사용자가 할 수 있는 일이 없어 필드 이름을 화면에 띄우지
 * 않는다 — 진단은 콘솔로 나간다 (`fetchResult`의 같은 가드와 한 규칙).
 */
export const FEEDBACK_CLIENT_MISSING = '후기를 보낼 수 없어요. 앱을 다시 시작해 주세요'

/** 서버가 규격 밖 응답을 준 경우. 배포 스큐라 재시도로 고쳐지지 않는다 */
export const FEEDBACK_HTTP_ERROR = (status: number) => `후기를 보내지 못했어요 (HTTP ${status})`

export const FEEDBACK_INVALID_BODY_EMPTY = '후기 내용을 적어 주세요.'
export const FEEDBACK_INVALID_BODY_LONG = '후기는 500자까지 적을 수 있어요.'
export const FEEDBACK_INVALID_RATING = '별점은 1점에서 5점 사이로 골라 주세요.'
export const FEEDBACK_INVALID_EMAIL = '이메일 주소를 다시 확인해 주세요.'
export const FEEDBACK_INVALID_EMAIL_LONG = '이메일 주소가 너무 길어요.'

/**
 * 429 대기 안내. `RetestAction`의 같은 줄과 문장 꼴을 맞춘다 — 앱 안에서 같은 뜻의 안내가
 * 두 화면에 각자 다른 말로 있으면 사용자는 다른 일이 일어난 줄 안다.
 */
export const feedbackWaitNotice = (seconds: number) => `${seconds}초 후 다시 보낼 수 있어요`
