package com.accentury.app.ui

import com.accentury.app.ui.components.ProgressDotState
import com.accentury.app.ui.components.dotState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 진행 도트의 세 상태 (KAN-161 2단계). 화면에서는 "진행이 한 칸 밀렸다"로만 드러나
 * 원인을 찾기 어려운 종류라 - 부등호 하나 차이다 - 경계를 여기서 고정한다.
 *
 * 채움 비율을 계산하던 `progressFraction`을 대신한다: 도트는 칸을 세는 물건이라
 * 비율이 아니라 "몇 번째 칸이 어떤 상태인가"가 진실이 됐다.
 */
class ProgressDotStateTest {
    @Test
    fun `현재 칸보다 앞은 완료다`() {
        assertEquals(ProgressDotState.Done, dotState(position = 1, current = 3))
        assertEquals(ProgressDotState.Done, dotState(position = 2, current = 3))
    }

    @Test
    fun `현재 칸은 하나뿐이다 - 반만 찬 칸이 둘이면 어디까지 왔는지 알 수 없다`() {
        assertEquals(ProgressDotState.Current, dotState(position = 3, current = 3))
    }

    @Test
    fun `현재 칸보다 뒤는 미완료다`() {
        assertEquals(ProgressDotState.Todo, dotState(position = 4, current = 3))
        assertEquals(ProgressDotState.Todo, dotState(position = 10, current = 3))
    }

    @Test
    fun `첫 문항은 첫 칸이 현재다 - 시작도 안 한 화면으로 보이지 않는다`() {
        assertEquals(ProgressDotState.Current, dotState(position = 1, current = 1))
        assertEquals(ProgressDotState.Todo, dotState(position = 2, current = 1))
    }

    @Test
    fun `마지막 문항을 끝내면 모든 칸이 완료다`() {
        (1..10).forEach { position ->
            assertEquals(ProgressDotState.Done, dotState(position = position, current = 11))
        }
    }
}
