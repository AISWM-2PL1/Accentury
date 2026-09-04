package com.accentury.app.recording

/**
 * 곡선 하나를 그리는 데 필요한 명령. 그리기 백엔드(Compose `Path`)와 기하 계산을 가르는 자리다.
 *
 * `Path`는 한 번 넣은 명령을 되읽을 수 없어 JVM 단위 테스트로 검사할 방법이 없다. 그래서
 * "어떤 곡선을 그릴지"는 여기서 값으로 만들고, [com.accentury.app.ui.components.CurveLane]은
 * 그 목록을 `Path`에 재생만 한다. 인과성(아래 [smoothPathCommands])은 눈으로 확인할 수 없는
 * 성질이라 테스트로 못박아 두어야 하는데, 값이 아니면 못박을 데가 없다.
 */
sealed interface PathCommand {
    /** 새 하위 경로를 ([x], [y])에서 시작한다. */
    data class MoveTo(val x: Float, val y: Float) : PathCommand

    /** 현재 점에서 ([x], [y])까지 직선. */
    data class LineTo(val x: Float, val y: Float) : PathCommand

    /** 현재 점에서 제어점 ([cx], [cy])를 거쳐 ([x], [y])까지 2차 베지어. */
    data class QuadTo(val cx: Float, val cy: Float, val x: Float, val y: Float) : PathCommand
}

/**
 * 선분 하나를 부드럽게 그리는 명령 목록으로 바꾼다 (KAN-105 3단계).
 * 좌표는 0..1 비율이고 여기서 [width]·[height]를 곱해 픽셀로 만든다.
 *
 * 표준 중간점(midpoint) 2차 베지어다. `MoveTo(p0)` → `LineTo(mid(p0,p1))` 로 시작해,
 * i = 1..n-2 각각에 대해 점 p[i]를 **제어점**으로, mid(p[i], p[i+1])을 **끝점**으로 쓰는
 * `QuadTo`를 잇고, 마지막에 `LineTo(p[n-1])`로 닫는다. 점이 2개면 곡선 조각 없이
 * `MoveTo(p0)` → `LineTo(mid)` → `LineTo(p1)`이고, 2개 미만이면 명령이 없다
 * (점 하나짜리 선분을 원으로 남기는 일은 그리는 쪽이 한다).
 *
 * ## 인과성
 *
 * 곡선 조각 하나가 (p[i-1], p[i], p[i+1]) 세 점만으로 확정된다. 그래서 32ms마다 점이
 * 하나씩 붙어도 **이미 그린 곡선 조각은 다시 계산되지 않는다** — n개 점의 명령 목록에서
 * 꼬리 `LineTo` 하나를 뺀 나머지가 n+1개 점 명령 목록의 접두사와 정확히 같다.
 *
 * 점이 붙을 때 실제로 다시 그려지는 곳은 **마지막 반 구간뿐**이다: 직전 중간점에서
 * 마지막 점까지 그은 임시 직선이, 다음 점이 오면 그 구간을 지나는 베지어로 바뀐다.
 * 시간으로는 프레임 간격의 절반, 곧 **16ms**다. 이 잔여 비인과 구간은 어떤 스무딩으로도
 * 없앨 수 없다 — 곡선 조각의 모양이 다음 점에 달려 있는 한 마지막 조각은 미정이고,
 * 정확히 0으로 만드는 방법은 점을 그대로 잇는 직선 폴리라인뿐인데 그 대가가 꺾임이다.
 * 16ms는 한 프레임도 못 되는 시간이라 눈에 꿈틀거림으로 보이지 않는다.
 *
 * 곡선이 점을 정확히 지나는 Catmull-Rom 대신 이 방식을 쓰는 이유는 둘이다.
 * 하나, 인과성 — Catmull-Rom은 구간 [p[i], p[i+1]]을 정하려고 p[i+2]까지 봐야 해서
 * 비인과 구간이 온전한 한 구간(32ms)으로 늘고, 꼬리가 매 프레임 눈에 띄게 꿈틀거린다.
 * 둘, 오버슈트 — Catmull-Rom은 제어점 밖으로 부풀어 실제 F0에 없는 봉우리를 만든다.
 * 2차 베지어는 볼록껍질 안에 머물러 값에 없는 음높이를 그리지 않는다.
 *
 * 대가는 곡선이 중간 점들을 정확히 지나지 않는다는 것인데, 피치 곡선은 개별 프레임 값을
 * 읽는 그래프가 아니라 억양의 모양을 보는 그림이라 꺾임이 사라지는 쪽이 낫다.
 */
fun smoothPathCommands(points: List<CurvePoint>, width: Float, height: Float): List<PathCommand> {
    if (points.size < 2) return emptyList()
    val x = { i: Int -> points[i].x * width }
    val y = { i: Int -> points[i].y * height }
    val midX = { i: Int -> (x(i) + x(i + 1)) / 2f }
    val midY = { i: Int -> (y(i) + y(i + 1)) / 2f }

    val commands = ArrayList<PathCommand>(points.size + 1)
    commands += PathCommand.MoveTo(x(0), y(0))
    // 첫 반 구간은 곡선이 될 짝(p[-1])이 없다 - 중간점까지 직선으로 간다.
    commands += PathCommand.LineTo(midX(0), midY(0))
    for (i in 1 until points.size - 1) {
        commands += PathCommand.QuadTo(x(i), y(i), midX(i), midY(i))
    }
    // 마지막 점도 제어점이 될 짝(p[n])이 없다 - 직전 중간점에서 곧장 이어 붙인다.
    // 점이 더 오면 이 한 줄만 베지어로 바뀐다(위 인과성 문단).
    commands += PathCommand.LineTo(x(points.size - 1), y(points.size - 1))
    return commands
}
