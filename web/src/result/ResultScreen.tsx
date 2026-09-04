/**
 * 결과 화면 (KAN-29). 테스트를 끝낸 사용자가 보는 마지막 화면이다 — 사투리 유사도 점수와
 * 공유 가능한 캐릭터형 등급을 보여주고, 공유와 다시 테스트로 내보낸다.
 *
 * ## 이 화면이 계산하지 않는 것
 *
 * 점수도 등급도 계산하지 않는다. 종합 점수 `(억양 × 2 + 단어) / 3`과 등급 경계값은 서버가
 * `scoreVersion`으로 고정해 판정한 값이고(KAN-21), 화면은 받은 숫자를 그리기만 한다
 * (AC 1항). 등급명 다섯 개도 여기 없다 — 서버가 `tier.name`으로 준다.
 *
 * ## 학습 레벨과 Lv 표기가 없다
 *
 * 이 테스트는 학습 진도가 아니라 사투리 유사도를 재는 것이라, 레벨업으로 읽히는 표현을
 * 쓰지 않는다 (KAN-29 요구, KAN-21 AC). 등급의 서열은 "5개 등급 중 4번째"처럼 순위로만
 * 적는다.
 *
 * ## 억양과 단어를 같은 형식으로 세운다
 *
 * 두 점수를 같은 컴포넌트([ScoreRow])로 두 번 그린다 (AC 2항). 한쪽을 크게 하거나 다른
 * 표현으로 그리면 화면이 "이쪽이 진짜 점수"라고 말하게 되는데, 종합 점수의 가중치(2:1)는
 * 서버 정책이지 화면이 강조로 거들 일이 아니다. 세로로 쌓는 것도 같은 이유이자, 좁은
 * 화면과 큰 글자 크기에서 두 칸이 서로를 찌그러뜨리지 않게 하기 위해서다 (AC 6항).
 */

import { useCallback, useEffect, useRef, useState } from 'react'
import { storeLabelFor, storeUrlFor, type StorePlatform } from '../audio/storeLink'
import type { FetchLike } from '../progress/fetchTestDefinition'
import { Button, StatusBlock, type ButtonVariant } from '../ui'
import { ShareIcon } from '../ui/icons'
import { fetchResult, ResultFetchError } from './fetchResult'
import { TIER_IMAGE_HEIGHT, TIER_IMAGE_WIDTH, tierImageFor } from './tierAssets'
import type { RetestControl } from './useRetest'
import type { TestResultView } from './testResult'

export interface ResultScreenProps {
  /** 백엔드 오리진 */
  apiBase: string
  /** 결과를 조회할 세션 */
  sessionId: string
  /** 세션 토큰 (Bearer 없이 값만). 출처 결정은 호출자 몫이다 — 앱에서는 브리지가 준다 */
  sessionToken: string
  /**
   * [친구에게 공유하기]. 카카오 공유 자체는 KAN-30이라, 이 화면은 버튼과 결과를 넘기는
   * 데까지만 한다. 선택 프로퍼티로 두지 않은 이유: 기본값을 빈 함수로 두면 눌러도 아무
   * 일이 없는 버튼이 화면에 남고, 그게 완성된 것처럼 보인다.
   */
  onShare: (result: TestResultView) => void
  /**
   * [다시 테스트하기]의 동작과 잠금 상태 (KAN-34).
   *
   * 핸들러 하나가 아니라 상태까지 통째로 받는 이유: 재응시는 네이티브 왕복이라 결과가 이
   * 화면으로 돌아오지 않고(성공하면 페이지가 통째로 교체된다), 실패 회신 수신자는 부모가
   * 설치한다 (§8). 화면은 받은 값을 그리기만 한다 — [useRetest]가 그 값을 만든다.
   */
  retest: RetestControl
  /**
   * [앱 다운로드]를 보낼 스토어. **값이 있으면 웹 단독 실행이다** (KAN-31).
   *
   * 판정도 감지도 부모가 한다 — 이 화면은 `navigator`를 읽지 않는다. 실행 판정(`standalone`)은
   * 이미 App이 들고 있고, UA 판별은 렌더마다 같은 답이 나오는 환경 조회라 화면 안에 두면
   * 테스트가 화면을 그릴 때마다 전역을 갈아끼워야 한다.
   *
   * 없으면(앱 안 WebView) 다운로드 CTA 자체를 그리지 않는다. 이미 앱인 사람에게 앱을 받으라는
   * 화면이 되어서는 안 되고, 그 실행의 전환 목표는 공유다 (KAN-30).
   */
  storePlatform?: StorePlatform
  /**
   * [앱 다운로드] 탭 계측 자리 (KAN-31 3단계 퍼널). 기본값은 아무것도 하지 않는 것이다 —
   * 링크의 이동은 이 콜백과 무관하게 일어나므로, 계측이 붙지 않은 지금도 버튼은 제 일을 한다.
   */
  onDownloadClick?: () => void
  /**
   * 결과가 처음으로 도착했다 (KAN-33 계측 자리). **성공한 첫 조회에 한 번만** 부른다 —
   * [다시 시도]로 다시 조회해도, 부모가 리렌더돼도 두 번 가지 않는다.
   *
   * 화면이 직접 세지 않고 콜백으로 올리는 이유는 `campaign` 때문이다. 유입 코드는 진입 URL이
   * 들고 있는 값이라 그 규칙을 아는 것은 App이고(`trackedCampaign`), 이 화면이 URL을 읽기
   * 시작하면 다운로드 CTA를 부모 판정으로 받는 규칙과 어긋난다.
   */
  onResultLoaded?: (result: TestResultView) => void
  /**
   * 주입용 fetch (테스트용).
   *
   * **참조가 안정적이어야 한다** — 이 값이 조회 이펙트의 의존성이라, 렌더마다 새로 만든
   * 인라인 함수를 넘기면 부모가 다시 그려질 때마다 결과를 다시 조회한다. 넘기지 않는 것이
   * 기본이고(App은 넘기지 않는다), 넘긴다면 렌더 밖에서 만든 값이어야 한다.
   */
  fetchImpl?: FetchLike
}

type LoadState =
  | { status: 'loading' }
  | { status: 'ready'; result: TestResultView }
  | { status: 'error'; error: ResultFetchError }

export function ResultScreen({
  apiBase,
  sessionId,
  sessionToken,
  onShare,
  retest,
  storePlatform,
  onDownloadClick,
  onResultLoaded,
  fetchImpl,
}: ResultScreenProps) {
  const [load, setLoad] = useState<LoadState>({ status: 'loading' })
  // [다시 시도]는 이 값을 올려 조회 이펙트를 다시 돌린다 (TestFlowScreen과 같은 방식).
  const [attempt, setAttempt] = useState(0)
  /*
   * 결과 도착을 이미 알렸는가. 계측은 "이 사람이 결과를 봤다" 한 건이라, 재조회나 토큰
   * 변경으로 이펙트가 다시 돌아도 한 번이어야 한다 (AC "중복 화면 노출로 이벤트가 과다
   * 발생하지 않는다").
   */
  const notified = useRef(false)

  useEffect(() => {
    // 재시도로 요청이 겹칠 때 먼저 뜬 응답이 뒤늦게 화면을 덮지 않도록 버린다.
    let cancelled = false
    setLoad({ status: 'loading' })
    fetchResult({ apiBase, sessionId, sessionToken }, fetchImpl)
      .then((result) => {
        if (cancelled) return
        setLoad({ status: 'ready', result })
        if (!notified.current) {
          notified.current = true
          onResultLoaded?.(result)
        }
      })
      .catch((error: unknown) => {
        if (!cancelled) setLoad({ status: 'error', error: asResultError(error) })
      })
    return () => {
      cancelled = true
    }
    /*
     * `onResultLoaded`는 의존성에 없다. 부모가 렌더마다 새로 만드는 인라인 콜백이라 넣으면
     * 부모가 다시 그려질 때마다 결과를 다시 조회한다 (`fetchImpl` 주석과 같은 함정이고,
     * 그쪽은 참조 안정을 요구해 풀었지만 이 콜백은 계측 한 번이라 그럴 값어치가 없다).
     * 한 번만 부르는 것은 위 `notified`가 보증한다.
     */
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [apiBase, sessionId, sessionToken, fetchImpl, attempt])

  const retry = useCallback(() => setAttempt((n) => n + 1), [])

  if (load.status === 'loading') {
    return (
      <main className="screen">
        <StatusBlock tone="waiting" message="결과를 불러오는 중…" />
      </main>
    )
  }

  if (load.status === 'error') {
    const { error } = load
    return (
      <main className="screen">
        <StatusBlock
          tone="error"
          /*
           * 만료만 문구를 갈아 끼운다 — 다른 실패는 "잠시 후 되면 보이는 것"이지만 만료는
           * 결과가 이미 지워져서(24시간, KAN-25) 영영 안 보이는 것이라, 기다리라는 뜻으로
           * 읽히면 안 된다.
           */
          message={error.expired ? '결과 보관 기간이 지났어요' : '결과를 불러오지 못했어요'}
          // 서버 문구를 그대로 쓴다 — 앱 배포 없이 안내를 바꿀 수 있어야 한다 (KAN-25).
          detail={error.message}
          /*
           * 재시도해서 달라질 수 있는 실패에만 [다시 시도]를 준다. 만료·세션 만료·미완료는
           * 같은 요청을 백 번 보내도 같은 응답이라, 버튼을 주면 사용자를 헛수고에 묶어 둔다.
           */
          /*
           * 만료(410)에서 [다시 테스트하기]를 주는 것이 KAN-29의 "재응시 유도"인데, 그
           * 버튼도 하단 버튼과 **같은 재응시 경로**를 탄다 — 만료 화면에만 옛 인트로 복귀가
           * 남아 있으면 세션을 새로 못 받은 채 인트로에 서게 된다.
           */
          action={
            error.retryable ? (
              <Button onClick={retry}>다시 시도</Button>
            ) : (
              <>
                {/*
                  브라우저에서 결과가 만료된 사람에게도 앱으로 가는 길을 준다 (KAN-31). 이
                  화면은 "결과가 없다"는 소식만 있고 볼 것이 남아 있지 않아, 재응시 말고 다른
                  출구가 없으면 앱 설치라는 전환 목표가 이 갈래에서만 통째로 빠진다.
                */}
                <AppDownloadAction platform={storePlatform} onDownloadClick={onDownloadClick} />
                {/*
                  다운로드가 주버튼인 자리에서는 재응시를 보조로 내린다 — 화면당 주버튼은
                  하나다 (ux-ui.md Hick's law). 앱 안에서는 여기가 그대로 주버튼이다.
                */}
                <RetestAction retest={retest} variant={storePlatform === undefined ? undefined : 'secondary'} />
              </>
            )
          }
        />
      </main>
    )
  }

  const { result } = load
  const { scores, tier } = result

  return (
    <main className="screen">
      <div className="screen__body">
        {/*
          등급 캐릭터 (KAN-162). KAN-29 때는 등급이 무엇이든 깃발 꽂은 사람 한 그림이었다 —
          등급별 그림이 낮은 등급에 "졌다"는 그림이 될까 봐서였다. KAN-162가 그 결정을
          뒤집었다: 확정된 다섯 캐릭터(외지인~경남 토박이, 노션 「결과화면 티어 이미지」)는
          서열의 그림이 아니라 정체성의 그림이다 — 갈매기에 쫓기는 외지인도 튜브 낀 여행객도
          각자 웃기고, 사용자가 공유하고 기억하는 것은 "내가 어느 캐릭터인가"다 (Peak-End:
          절정 = 캐릭터 공개, ux-ui.md).
        */}
        <TierCharacter code={tier.code} name={tier.name} />

        <div>
          {/*
            등급 이름이 이 화면에서 가장 큰 글자다 (KAN-161 3단계, 아트보드 `Result.dc.html`).
            예전에는 종합 점수 도넛이 제일 컸는데, 사용자가 공유하고 기억하는 것은 숫자가
            아니라 "명예주민"이라는 이름이다 — 점수는 그 이름의 근거로 아래 카드에 든다.
          */}
          <h1 className="type-display">{tier.name}</h1>
          {/* 서열은 순위로만 적는다 — 레벨업으로 읽히는 표기를 쓰지 않는다 */}
          <p className="type-caption result-tier__rank">
            {tier.of}개 등급 중 {tier.rank}번째
          </p>
          <p className="type-body-sm result-tier__comment">{result.comment}</p>
        </div>

        {/*
          점수 카드 — 왼쪽에 종합, 오른쪽에 내역. 억양과 단어는 그 안에서 같은 형식·같은
          크기다 (AC 2항). 도넛을 카드 안으로 들인 것이 3단계의 변화다: 화면 위에 따로 떠
          있을 때는 종합 점수와 두 세부 점수가 서로 다른 두 가지처럼 보였는데, 실제로는
          한쪽이 다른 쪽의 가중 평균이라 한 상자에 있는 편이 관계를 말한다.
        */}
        <div className="card result-card">
          <ScoreDonut score={scores.overall} />
          <div className="result-scores">
            <ScoreRow label="억양" score={scores.intonation} />
            <ScoreRow label="단어" score={scores.vocabulary} />
          </div>
        </div>

        {/*
          결과가 어느 정의·어느 점수 정책의 판정인지 남긴다. 사용자가 읽을 값은 아니지만
          문의가 들어왔을 때 이 두 값이 없으면 어떤 눈금으로 나온 등급인지 되짚을 수 없다
          (KAN-21의 sv-0.4 재보정처럼 정책은 바뀐다).
        */}
        <p className="type-caption result-version">
          {result.testVersion} · {result.scoreVersion}
        </p>
      </div>

      <div className="screen__footer">
        <AppDownloadAction platform={storePlatform} onDownloadClick={onDownloadClick} />
        {/*
          공유 버튼의 무게가 실행에 따라 갈린다. 브라우저에서는 이 화면의 전환 목표가 앱 설치라
          (KAN-31) 다운로드가 주버튼을 가져가고 공유는 보조로 내려간다 — 주버튼이 둘이면 어느
          쪽도 주버튼이 아니게 된다 (ux-ui.md Hick's law: 화면당 Primary CTA 1개). 앱 안에서는
          설치를 권할 이유가 없으므로 공유가 그대로 주버튼이다 (KAN-30).
        */}
        <Button
          variant={storePlatform === undefined ? 'primary' : 'secondary'}
          onClick={() => onShare(result)}
          style={{ width: '100%' }}
        >
          {/* 아이콘이 라벨 앞에 선다 (아트보드). `.btn`의 gap 8이 사이를 잡고, 아이콘은
              `currentColor`라 주버튼에서는 크림, 보조로 내려가면 잉크로 그려진다 */}
          <ShareIcon />
          <span>친구에게 공유하기</span>
        </Button>
        {/*
          학습 시작이 아니라 다시 테스트다 — 이 화면에서 나가는 길은 공유와 재응시뿐이다.

          글자만 있던 버튼을 보조 버튼으로 올렸다 (KAN-161 3단계, 시안 v3). 글자 버튼은
          눌리는 것인지가 모양으로 드러나지 않는데, 이 자리는 결과를 다 본 사람이 실제로
          누르는 두 번째 출구다 — 테두리 1.5px 하나가 그 어포던스를 준다. 그림자는 여전히
          주 버튼만 갖는다 (정본 §8: 떠 있는 종이가 둘이면 어느 쪽을 눌러야 할지 흐려진다).
        */}
        <RetestAction retest={retest} variant="secondary" />
      </div>
    </main>
  )
}

/**
 * 등급 캐릭터 한 장 (KAN-162 2단계).
 *
 * `alt`는 서버의 `tier.name`이다 — 등급명은 서버 것이고(KAN-29), 그림이 무엇을 그렸는지를
 * 말로 하면 결국 등급명이다. 바로 아래 h1이 같은 이름을 크게 적지만 그건 중복이 아니라
 * 그림의 설명이다: 스크린 리더는 "명예주민 그림, 제목 명예주민"으로 읽고, 그림 없는 사람에게
 * 이 자리가 무엇이었는지 알려 주는 것이 alt의 일이다.
 *
 * ## 폴백은 등급명 텍스트다
 *
 * 그림이 없거나(모르는 code) 로딩에 실패하면 같은 슬롯에 등급명을 크게 적는다 (Req 3).
 * 깨진 이미지 아이콘도, 빈 자리도 아니다 — 자리를 비워 두면 화면이 위로 튀어 오르고,
 * 브라우저의 깨진 아이콘은 우리 그림이 아니다. `aria-hidden`인 이유: 이 텍스트는 그림의
 * 자리를 눈으로 메우는 것이고, 소리로는 바로 아래 h1이 이미 같은 이름을 읽는다. 여기까지
 * 읽어 주면 등급명이 두 번 나온다.
 *
 * ## halo
 *
 * 그림은 투명 배경 WebP고 종이·잉크 색이 박혀 있다 (정본 §7 `ILLO` — 일러스트는 테마를
 * 따르지 않는다). 다크 팔레트가 붙는 날 검정 선이 어두운 배경에 묻히는 것을 막는 것이
 * `.tier-character__image`의 크림 2px halo다.
 * 라이트에서는 halo가 배경과 같은 크림이라 보이지 않는다 — 지금 넣어 두는 이유는 다크가
 * 오는 날 이 화면을 다시 열지 않기 위해서다.
 */
function TierCharacter({ code, name }: { code: string; name: string }) {
  const src = tierImageFor(code)
  // 로딩 실패는 렌더 중에 알 수 없다 — onError가 뜬 뒤에야 폴백으로 바꾼다.
  const [failed, setFailed] = useState(false)

  // code가 바뀌면(재응시 후 다른 결과) 이전 실패가 새 그림을 가리면 안 된다.
  useEffect(() => {
    setFailed(false)
  }, [src])

  return (
    <div className="illustration illustration--result">
      {src !== undefined && !failed ? (
        <img
          className="tier-character__image"
          src={src}
          alt={name}
          width={TIER_IMAGE_WIDTH}
          height={TIER_IMAGE_HEIGHT}
          decoding="async"
          onError={() => setFailed(true)}
        />
      ) : (
        <span className="tier-character__fallback type-display" aria-hidden="true">
          {name}
        </span>
      )}
    </div>
  )
}

/**
 * [앱 다운로드] 한 벌 — 스토어 링크와 그 아래 "어디로 가는지" 한 줄 (KAN-31 2단계).
 *
 * 웹 단독 실행에서만 나온다. `platform`이 없으면 아무것도 그리지 않는다 — 두 자리(만료 화면,
 * 하단)가 각자 `undefined`를 검사하면 한쪽만 빠뜨린 화면이 생긴다.
 *
 * 버튼이 아니라 `<a>`인 이유는 [MicBlockedScreen]과 같다: 스토어로 나가는 것은 이동이라
 * 링크의 기본 동작(새 탭·길게 눌러 복사·스크린 리더의 "링크" 안내)이 전부 의미를 갖는다.
 * `onClick`으로 location을 바꾸면 그게 전부 사라진다. 생김새만 주버튼과 맞춘다.
 *
 * 스토어 URL을 여기서 조립하지 않는다 — 규칙은 [storeUrlFor]가 소유한다. 하드코딩이 두 군데로
 * 갈리면 앱 패키지명이 바뀌는 날 한쪽만 고쳐진다.
 */
function AppDownloadAction({
  platform,
  onDownloadClick,
}: {
  platform?: StorePlatform
  onDownloadClick?: () => void
}) {
  if (platform === undefined) return null

  return (
    <>
      <a
        className="btn btn--primary"
        href={storeUrlFor(platform)}
        style={{ width: '100%' }}
        /*
          계측만 걸리는 자리다 (KAN-31 3단계). 이동을 막지 않으므로 여기서 무슨 일이 일어나든
          링크는 제 일을 한다 — 계측 코드의 예외가 설치 전환을 끊지 않는 것이 요점이다.
        */
        onClick={onDownloadClick}
      >
        앱 다운로드
      </a>
      <p className="type-caption" style={{ color: 'var(--color-muted-foreground)' }}>
        {storeLabelFor(platform)}로 이동해요
      </p>
    </>
  )
}

/**
 * [다시 테스트하기] 한 벌 — 버튼과 그 아래 안내 한두 줄 (KAN-34).
 *
 * 만료 화면(410)과 하단, 두 자리가 이걸 그대로 쓴다. 두 곳이 각자 그리면 한쪽만 잠긴 화면이
 * 생기는데, 더블탭 방지가 클라이언트 몫이라(KAN-107) 한 자리라도 새면 방어가 아니게 된다.
 */
function RetestAction({ retest, variant }: { retest: RetestControl; variant?: ButtonVariant }) {
  const { onRetest, disabled, pending, message, retryAfterSec } = retest

  return (
    <>
      <Button variant={variant} onClick={onRetest} disabled={disabled}>
        {/*
          성공하면 회신이 아니라 페이지 교체가 온다. 그 사이 create 왕복 동안 화면은 아무것도
          모르므로, 할 수 있는 말은 "받았고 진행 중"까지다 — 몇 초 걸리는지도 알 수 없다.
        */}
        {pending ? '준비 중…' : '다시 테스트하기'}
      </Button>

      {message !== null && (
        /*
          네이티브가 준 문구를 그대로 그린다 — 갈래별 카피를 웹이 따로 들면 같은 판정에 두
          벌이 생겨 앱과 웹이 다른 말을 하게 된다 (RetestFailure 계약).

          role="alert"인 이유는 StatusBlock의 오류 문구와 같다: 이미 떠 있는 화면에서 나중에
          나타나는 실패라, 스스로 읽어 주지 않으면 버튼이 왜 죽었는지 알 길이 없다.
        */
        <p className="type-caption result-retest__message" role="alert">
          {message}
        </p>
      )}

      {retryAfterSec > 0 && (
        /*
          429 대기 안내 (§2.5). live 영역에 두지 않는다 — 1초마다 바뀌는 값이라 읽어 주면
          같은 문장을 매초 반복해 위 실패 문구를 덮는다.
        */
        <p className="type-caption result-retest__wait">{retryAfterSec}초 후 다시 시도할 수 있어요</p>
      )}
    </>
  )
}

/**
 * 종합 점수 도넛.
 *
 * `<svg>`로 그리는 이유: 원호의 길이를 stroke-dasharray로 정확히 잘라낼 수 있고, 확대·
 * 접근성 글자 크기에서도 화질이 유지된다. viewBox 좌표(120·54 같은 값)는 디자인 토큰이
 * 아니라 도형의 좌표계라 그대로 적는다 — 화면에 나가는 실제 크기는 CSS가 정한다.
 *
 * 숫자를 도넛 안에 텍스트로도 적는다. 원호만으로는 72와 75를 구분할 수 없고, 원호는
 * 스크린 리더에 아무것도 아니다.
 */
function ScoreDonut({ score }: { score: number }) {
  const radius = 54
  const circumference = 2 * Math.PI * radius
  // 서버가 0~100을 보장하지만(§3.7), 배포 사고로 벗어난 값이 오면 원호가 한 바퀴를 넘어
  // 그려진다. 그림만 가둔다 — 숫자는 받은 그대로 보여야 이상을 알아챌 수 있다.
  const filled = Math.min(Math.max(score, 0), 100)

  return (
    <div className="score-donut">
      <svg className="score-donut__ring" viewBox="0 0 120 120" aria-hidden="true">
        <circle className="score-donut__track" cx="60" cy="60" r={radius} />
        <circle
          className="score-donut__value"
          cx="60"
          cy="60"
          r={radius}
          strokeDasharray={circumference}
          strokeDashoffset={circumference * (1 - filled / 100)}
        />
      </svg>
      <div className="score-donut__label">
        {/*
          "종합 점수" 캡션을 눈에서만 지웠다 (KAN-161 3단계, 아트보드). 96px 원 안에 두 줄이
          들어가면 숫자가 캡션만 해지고, 옆 칸에 "억양"·"단어"가 이미 이름표를 달고 서 있어
          왼쪽 큰 숫자가 무엇인지는 배치로 읽힌다.

          소리로는 그 배치가 없다. 그래서 지운 것은 화면에서뿐이고 스크린 리더에는 남긴다 —
          없으면 "74점"이 무엇의 74점인지 말해 주는 것이 아무것도 없다.
        */}
        <span className="sr-only">종합 점수</span>
        <span className="type-headline score-donut__score">{score}점</span>
      </div>
    </div>
  )
}

/**
 * 점수 한 줄. 억양과 단어가 이걸 그대로 두 번 쓴다 (AC 2항).
 *
 * `<progress>`를 쓰는 이유는 [ProgressIndicator]와 같다 — role=progressbar와 value/max
 * 의미론을 브라우저가 그냥 준다. 다만 진행률이 아니라 점수라 max가 항상 100이고,
 * 라벨에 "점수"를 넣어 진행 상황으로 읽히지 않게 한다.
 */
function ScoreRow({ label, score }: { label: string; score: number }) {
  return (
    <div className="score-row">
      <div className="score-row__head">
        <span className="type-label score-row__label">{label}</span>
        {/* 점수는 Jua로, 라벨보다 한 급 크게 (아트보드). 카드 안 오른쪽 칸이 좁아 예전 크기
            (title 30)로는 "억양"과 "78점"이 한 줄에 들어가지 않는다 */}
        <span className="type-title-sm score-row__value">{score}점</span>
      </div>
      <progress
        className="score-row__bar"
        aria-label={`${label} 점수`}
        value={Math.min(Math.max(score, 0), 100)}
        max={100}
      />
    </div>
  )
}

/**
 * 던져진 값을 [ResultFetchError]로 좁힌다.
 *
 * [fetchResult]는 실패를 전부 이 타입으로 던지지만, 렌더 중에 난 다른 예외가 이 자리로
 * 흘러들 수 있다. 그때 `error.expired`가 undefined가 되면 만료 분기가 조용히 어긋나므로,
 * 모르는 값은 재시도 가능한 일반 실패로 감싼다.
 */
function asResultError(error: unknown): ResultFetchError {
  if (error instanceof ResultFetchError) return error
  return new ResultFetchError(error instanceof Error ? error.message : String(error), null, true)
}
