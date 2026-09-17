/**
 * 이용 후기 바텀시트 (KAN-211 3단계). 결과 화면 위를 덮는 모달이다.
 *
 * ## 왜 페이지 안 시트인가
 *
 * 앱 WebView는 새 창을 열지 못한다 (`setSupportMultipleWindows`가 꺼져 있다 — webview-layer.md
 * §7). 바깥 설문 링크로 보내면 앱에서는 아무 일도 일어나지 않고, 브라우저에서도 응시자가 우리
 * 화면을 떠난다. 그래서 받는 자리를 화면 안에 둔다 — `ads/AdConsentSheet`와 같은 꼴이고, 시트의
 * 막·패널·버튼 스택이 저쪽에서 그대로 온 것도 그 때문이다.
 *
 * ## 닫는 길이 여럿인 것이 광고 시트와 다른 점이다
 *
 * 광고 동의 시트는 Escape도 배경 탭도 듣지 않는다 — 고르지 않고 닫으면 다음 실행에 또 뜨기
 * 때문이다. 후기는 반대다. 안 쓰고 나가는 것이 정상 행동이고 다시 조르지도 않으므로, 닫는 길을
 * 막으면 시트가 사용자를 붙잡아 두는 물건이 된다. 그래서 [닫기] 버튼·Escape·배경 탭 셋 다
 * 듣는다 — 다만 보내는 중에는 전부 무시한다: 요청이 서버에 닿았는지 모르는 채로 화면이 사라지면
 * 사용자는 후기가 갔는지 알 수 없다.
 *
 * ## 접근성은 광고 시트와 같은 수준까지만
 *
 * `role="dialog"` + `aria-modal="true"`로 뒤의 결과 화면을 보조기기에서 떼어 놓고, 제목을
 * `aria-labelledby`로 잇고, 열리는 순간 초점을 시트 안(본문 입력칸)에 둔다. **스크롤 잠금과
 * 포커스 트랩은 만들지 않는다** — 이 레포의 다른 모달(`AdConsentSheet`)에 없는 장치이고,
 * 한쪽 시트에만 세우면 같은 모양의 두 화면이 키보드에서 다르게 움직인다. 세운다면 둘 다
 * 같이 세우는 별도 작업이다.
 *
 * ## 멱등 키를 시트가 만들지 않는다
 *
 * [submit]을 받기만 한다. 키는 부모(`ResultScreen`)가 시트를 열 때 한 번 만들어 재시도에
 * 재사용하는데, 그래야 [다시 보내기]가 첫 요청과 같은 키로 나간다 — 새 키로 나가면 첫 요청이
 * 실제로 저장됐던 경우(응답만 유실) 409를 받아 "이미 보냈어요"가 되고, 방금 쓴 글이 어디로
 * 갔는지 알 수 없게 된다 (`sendFeedback`의 `idempotencyKey` 주석).
 */

import { useState, type FormEvent, type KeyboardEvent, type MouseEvent } from 'react'
import { PrivacyPolicyLink } from '../legal/PrivacyPolicyLink'
import { Button } from '../ui'
import {
  FEEDBACK_ALREADY,
  FEEDBACK_BODY_PLACEHOLDER,
  FEEDBACK_CLOSE,
  FEEDBACK_DETAIL_LEAD,
  FEEDBACK_DETAIL_TAIL,
  FEEDBACK_DONE_BODY,
  FEEDBACK_DONE_TITLE,
  FEEDBACK_EMAIL_HINT,
  FEEDBACK_EMAIL_LABEL,
  FEEDBACK_EXPIRED,
  FEEDBACK_GUIDE,
  FEEDBACK_RATING_LABEL,
  FEEDBACK_RETRY,
  FEEDBACK_SENDING,
  FEEDBACK_SUBMIT,
  FEEDBACK_TITLE,
  feedbackWaitNotice,
} from './feedbackText'
import {
  FeedbackApiError,
  FEEDBACK_BODY_MAX,
  FEEDBACK_EMAIL_MAX,
  FEEDBACK_SESSION_EXPIRED,
  FEEDBACK_SESSION_FORBIDDEN,
  validateFeedbackInput,
  type FeedbackInput,
  type SendFeedbackResult,
} from './sendFeedback'

const TITLE_ID = 'feedback-title'
const GUIDE_ID = 'feedback-guide'
const EMAIL_HINT_ID = 'feedback-email-hint'

/** 별점 눈금. 5단계인 것은 기획 결정이고, 배열로 두는 것은 JSX에서 세지 않기 위해서다 */
const RATINGS = [1, 2, 3, 4, 5] as const

/**
 * 시트가 지금 어느 상태인가.
 *
 * `sent`·`already`·`expired`는 전부 **되돌아갈 수 없는 끝**이다 — 입력칸을 치우고 닫기만
 * 남긴다. `failed`만 입력을 그대로 둔 채 다시 보낼 길을 준다: 사용자가 방금 쓴 글이
 * 실패 한 번에 사라지면 두 번 쓰지 않는다.
 */
export type SheetState =
  | { status: 'editing' }
  | { status: 'sending' }
  | { status: 'sent' }
  | { status: 'already' }
  | { status: 'expired' }
  | { status: 'failed'; message: string; retryable: boolean; retryAfterMs: number | null }

export interface FeedbackSheetProps {
  /** 떠 있는가. false면 아무것도 그리지 않는다 */
  open: boolean
  /** 닫아 달라. 실제로 닫는 것은 부모다 (열림 상태를 부모가 든다) */
  onClose: () => void
  /**
   * 실제 전송. 시트가 `sendFeedback`을 직접 부르지 않는 이유는 멱등 키다 (파일 헤더) —
   * 세션 값과 키를 아는 것은 부모이고, 시트는 사용자가 적은 것만 넘긴다.
   */
  submit: (input: FeedbackInput) => Promise<SendFeedbackResult>
  /**
   * 보내기가 끝났다 (저장이든 "이미 있음"이든). 계측과 진입 버튼 교체는 부모 몫이라
   * 결과와 입력을 그대로 올린다 — 시트는 `track`을 모른다 (`onDownloadClick`과 같은 성격).
   */
  onSubmitted?: (result: SendFeedbackResult, input: FeedbackInput) => void
  /** 처음 상태 (테스트·부모 복원용). 기본은 작성 중이다 */
  initialState?: SheetState
}

export function FeedbackSheet({ open, onClose, submit, onSubmitted, initialState }: FeedbackSheetProps) {
  const [state, setState] = useState<SheetState>(initialState ?? { status: 'editing' })
  const [rating, setRating] = useState<number | null>(null)
  const [body, setBody] = useState('')
  const [email, setEmail] = useState('')

  if (!open) return null

  const sending = state.status === 'sending'
  const finished = state.status === 'sent' || state.status === 'already' || state.status === 'expired'

  const input: FeedbackInput = {
    rating,
    body: body.trim(),
    contactEmail: email.trim() === '' ? null : email.trim(),
  }
  const invalid = validateFeedbackInput(input)
  /*
   * 아직 아무것도 안 적은 사람에게 "후기 내용을 적어 주세요"를 띄우지 않는다. 열자마자
   * 빨간 줄이 보이면 시트가 사용자를 나무라는 것처럼 읽히는데(ux-ui.md 비난 없는 카피),
   * 아직 아무 일도 일어나지 않았다. 버튼이 비활성인 것으로 충분하다.
   */
  const touched = body !== '' || email !== ''

  /** 닫아도 되는가를 한자리에서 본다 — [닫기]·Escape·배경 탭이 같은 규칙을 따라야 한다 */
  function requestClose() {
    if (sending) return
    onClose()
  }

  function handleBackdropClick(event: MouseEvent<HTMLDivElement>) {
    // 패널 안을 누른 것이 막까지 올라온 경우를 거른다 — 글을 쓰다 누른 자리에서 시트가 닫히면
    // 쓰던 것이 사라진다.
    if (event.target === event.currentTarget) requestClose()
  }

  function handleKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    // 초점이 시트 안에서 시작하므로 keydown은 여기까지 올라온다 — 전역 리스너가 필요 없다.
    if (event.key === 'Escape') requestClose()
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (invalid !== null || sending) return

    setState({ status: 'sending' })
    try {
      const result = await submit(input)
      setState({ status: result.status === 'saved' ? 'sent' : 'already' })
      onSubmitted?.(result, input)
    } catch (error: unknown) {
      setState(toFailure(error))
    }
  }

  return (
    <div className="feedback-sheet" onClick={handleBackdropClick} onKeyDown={handleKeyDown}>
      {/*
        `noValidate`로 브라우저 기본 풍선을 끈다. `required`·`type="email"`은 의미론으로
        남겨 두되 판정은 우리 것 하나로 한다 — 같은 규칙을 브라우저 문구와 우리 문구가 서로
        다르게 말하면 사용자는 둘 중 무엇을 고쳐야 하는지 알 수 없다.
      */}
      <form
        className="feedback-panel"
        role="dialog"
        aria-modal="true"
        aria-labelledby={TITLE_ID}
        noValidate
        onSubmit={handleSubmit}
      >
        {/* h1이 아니라 h2인 이유는 AdConsentSheet와 같다 — 결과 화면의 h1(등급명)이 화면
            이름이고 이 시트는 그 위에 덮인 것이다 */}
        <h2 id={TITLE_ID} className="type-title-sm feedback-title">
          {state.status === 'sent' ? FEEDBACK_DONE_TITLE : FEEDBACK_TITLE}
        </h2>

        {finished ? (
          <div className="type-body-sm feedback-body">
            {/* 끝난 소식은 스스로 읽어 준다 — 시트가 이미 떠 있는 채로 내용만 바뀌는 자리라
                초점 이동이 없고, 그러면 화면을 보지 않는 사람에게는 아무 일도 안 일어난 것이다 */}
            <p role="alert">
              {state.status === 'sent' && FEEDBACK_DONE_BODY}
              {state.status === 'already' && FEEDBACK_ALREADY}
              {state.status === 'expired' && FEEDBACK_EXPIRED}
            </p>
          </div>
        ) : (
          <>
            <div className="type-body-sm feedback-body">
              <p id={GUIDE_ID}>{FEEDBACK_GUIDE}</p>
            </div>

            {/* 별점은 라디오 그룹이다 — 화살표 키 이동과 "5개 중 3번째" 안내를 브라우저가 그냥
                준다. ★는 눈에만 보이고(`aria-hidden`), 소리로는 "3점"이 읽힌다 */}
            <fieldset className="feedback-field" disabled={sending}>
              <legend className="type-label">{FEEDBACK_RATING_LABEL}</legend>
              <div className="feedback-rating">
                {RATINGS.map((value) => (
                  <label key={value} className="feedback-rating__star">
                    <input
                      className="sr-only"
                      type="radio"
                      name="rating"
                      value={value}
                      checked={rating === value}
                      onChange={() => setRating(value)}
                    />
                    <span aria-hidden="true">{rating !== null && value <= rating ? '★' : '☆'}</span>
                    <span className="sr-only">{value}점</span>
                  </label>
                ))}
              </div>
            </fieldset>

            <div className="feedback-field">
              {/*
                이름표를 따로 적지 않고 위 안내 문장을 `aria-labelledby`로 잇는다 — 그 문장이
                이미 "무엇을 적는 칸인지"를 말하고 있어서, 라벨을 하나 더 두면 같은 말이 두 번
                읽힌다. 시트가 뜨는 순간 초점이 여기 선다 (파일 헤더의 접근성).
              */}
              <textarea
                autoFocus
                className="feedback-textarea"
                aria-labelledby={GUIDE_ID}
                placeholder={FEEDBACK_BODY_PLACEHOLDER}
                maxLength={FEEDBACK_BODY_MAX}
                required
                rows={4}
                value={body}
                disabled={sending}
                onChange={(event) => setBody(event.target.value)}
              />
              {/* 남은 글자가 아니라 쓴 글자를 센다 — 상한이 압박이 아니라 눈금으로 읽히게 */}
              <p className="type-caption feedback-counter">{`${body.length}/${FEEDBACK_BODY_MAX}`}</p>
            </div>

            <div className="feedback-field">
              <label className="type-label" htmlFor="feedback-email">
                {FEEDBACK_EMAIL_LABEL}
              </label>
              <input
                id="feedback-email"
                className="feedback-input"
                type="email"
                inputMode="email"
                autoComplete="email"
                aria-describedby={EMAIL_HINT_ID}
                maxLength={FEEDBACK_EMAIL_MAX}
                value={email}
                disabled={sending}
                onChange={(event) => setEmail(event.target.value)}
              />
              {/* 무엇에 쓰는 값인지 적는 것이 수집 고지다 — 칸 옆에서 말해야 적기 전에 읽힌다 */}
              <p id={EMAIL_HINT_ID} className="type-caption feedback-hint">
                {FEEDBACK_EMAIL_HINT}
              </p>
            </div>

            <div className="type-body-sm feedback-body">
              <p>
                {FEEDBACK_DETAIL_LEAD} <PrivacyPolicyLink />
                {FEEDBACK_DETAIL_TAIL}
              </p>
            </div>

            {/* 아직 보내지 않은 상태의 안내라 live 영역에 두지 않는다 — 글자를 지우고 쓰는
                동안 같은 문장이 매번 읽히면 입력을 방해한다 */}
            {invalid !== null && touched && <p className="type-caption feedback-message">{invalid}</p>}
          </>
        )}

        {/*
          실패 문구는 스스로 읽어 준다 (RetestAction과 같은 이유) — 이미 떠 있는 시트에 나중에
          나타나는 실패라, 읽어 주지 않으면 버튼을 눌렀는데 왜 아무 일도 없었는지 알 길이 없다.
        */}
        {state.status === 'failed' && (
          <p className="type-caption feedback-message" role="alert">
            {state.message}
          </p>
        )}
        {state.status === 'failed' && state.retryAfterMs !== null && (
          /*
            429 대기 안내. 초를 세어 내리지 않는다 — 여기에는 스케줄러가 없고, 매초 바뀌는
            숫자는 위 실패 문구를 덮어 읽는다 (`RetestAction`의 같은 판단).
          */
          <p className="type-caption feedback-hint">{feedbackWaitNotice(Math.ceil(state.retryAfterMs / 1000))}</p>
        )}

        {/*
          버튼을 세로로 쌓는 것도 `.ad-consent-actions`와 같은 이유다 — 가로로 나누면 각 버튼이
          반 폭이 되고, 엄지가 먼저 닿는 위쪽이 주동작 자리다.
        */}
        <div className="feedback-actions">
          {!finished && !(state.status === 'failed' && !state.retryable) && (
            <Button type="submit" disabled={sending || invalid !== null}>
              {sending ? FEEDBACK_SENDING : state.status === 'failed' ? FEEDBACK_RETRY : FEEDBACK_SUBMIT}
            </Button>
          )}
          <Button variant="secondary" disabled={sending} onClick={requestClose}>
            {FEEDBACK_CLOSE}
          </Button>
        </div>
      </form>
    </div>
  )
}

/**
 * 던져진 값을 시트 상태로 옮긴다.
 *
 * 401·403만 따로 뺀다 — 다른 실패는 잠시 뒤 되거나 문구를 고치면 되지만, 세션이 없어진 것은
 * 이 결과에 후기를 남길 길 자체가 닫힌 것이라 [다시 보내기]를 주면 안 될 일을 반복시킨다.
 * 두 코드를 같이 묶는 이유: 사용자 입장에서 "내 세션이 아니다"와 "내 세션이 만료됐다"는
 * 할 수 있는 일이 같다 — 다시 테스트하는 것뿐이다.
 *
 * [FeedbackApiError]가 아닌 값이 흘러들면 재시도 가능한 일반 실패로 감싼다 (`asResultError`와
 * 같은 방어) — 그러지 않으면 `retryable`이 undefined가 되어 재시도 버튼이 조용히 사라진다.
 */
function toFailure(error: unknown): SheetState {
  const apiError =
    error instanceof FeedbackApiError
      ? error
      : new FeedbackApiError(error instanceof Error ? error.message : String(error), { retryable: true })

  if (apiError.code === FEEDBACK_SESSION_EXPIRED || apiError.code === FEEDBACK_SESSION_FORBIDDEN) {
    return { status: 'expired' }
  }
  return {
    status: 'failed',
    message: apiError.message,
    retryable: apiError.retryable,
    retryAfterMs: apiError.retryAfterMs,
  }
}
