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

import { useCallback, useEffect, useState } from 'react'
import { storeLabelFor, storeUrlFor, type StorePlatform } from '../audio/storeLink'
import type { FetchLike } from '../progress/fetchTestDefinition'
import { Button, StatusBlock, type ButtonVariant } from '../ui'
import { fetchResult, ResultFetchError } from './fetchResult'
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
  fetchImpl,
}: ResultScreenProps) {
  const [load, setLoad] = useState<LoadState>({ status: 'loading' })
  // [다시 시도]는 이 값을 올려 조회 이펙트를 다시 돌린다 (TestFlowScreen과 같은 방식).
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    // 재시도로 요청이 겹칠 때 먼저 뜬 응답이 뒤늦게 화면을 덮지 않도록 버린다.
    let cancelled = false
    setLoad({ status: 'loading' })
    fetchResult({ apiBase, sessionId, sessionToken }, fetchImpl)
      .then((result) => {
        if (!cancelled) setLoad({ status: 'ready', result })
      })
      .catch((error: unknown) => {
        if (!cancelled) setLoad({ status: 'error', error: asResultError(error) })
      })
    return () => {
      cancelled = true
    }
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
        <ScoreDonut score={scores.overall} />

        <div>
          <h1 className="type-headline">{tier.name}</h1>
          {/* 서열은 순위로만 적는다 — 레벨업으로 읽히는 표기를 쓰지 않는다 */}
          <p className="type-caption result-tier__rank">
            {tier.of}개 등급 중 {tier.rank}번째
          </p>
          <p className="type-body-sm result-tier__comment">{result.comment}</p>
        </div>

        {/* 억양과 단어를 같은 형식으로, 같은 크기로 (AC 2항) */}
        <div className="card result-scores">
          <ScoreRow label="억양" score={scores.intonation} />
          <ScoreRow label="단어" score={scores.vocabulary} />
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
          친구에게 공유하기
        </Button>
        {/* 학습 시작이 아니라 다시 테스트다 — 이 화면에서 나가는 길은 공유와 재응시뿐이다 */}
        <RetestAction retest={retest} variant="text" />
      </div>
    </main>
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
        <span className="type-display score-donut__score">{score}</span>
        <span className="type-caption score-donut__unit">종합 점수</span>
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
        <span className="type-body score-row__label">{label}</span>
        <span className="type-title score-row__value">{score}</span>
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
