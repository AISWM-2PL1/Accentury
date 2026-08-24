package com.accentury.app.net

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OkHttp 호출 하나를 코루틴으로 잇는다.
 *
 * enqueue는 OkHttp 디스패처 스레드에서 돌기 때문에 호출자 스레드를 막지 않는다. 코루틴이 취소되면
 * 호출도 함께 끊어 소켓까지 내려간다 — 업로드 폐기(FR-DP-02)와 세션 생성 화면 이탈이 둘 다 이
 * 경로로 전송을 실제로 멈춘다.
 *
 * 여기 있는 이유: 업로드 클라이언트(KAN-23)가 쓰던 것을 세션 클라이언트(KAN-34)가 그대로 필요로 한다.
 * 파일마다 사본을 두면 취소·예외 전파 같은 미묘한 규칙이 두 벌로 갈라지므로 한 자리에 둔다.
 */
internal suspend fun OkHttpClient.await(request: Request): Response =
    suspendCancellableCoroutine { continuation ->
        val call = newCall(request)
        continuation.invokeOnCancellation { call.cancel() } // 코루틴 취소 -> http 호출을 끊어버림
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isCancelled) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response) { _, _, _ -> response.close() }
                }
            },
        )
    }
