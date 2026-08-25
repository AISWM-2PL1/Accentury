package com.accentury.app.net

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 예외 종류를 사용자 문구의 갈래로 옮기는 판정만 본다 (KAN-147 2단계).
 * 실제로 어떤 예외가 올라오는지는 [com.accentury.app.upload.OkHttpUploadClientTest]가 확인한다.
 */
class TransportFailureTest {

    @Test
    fun `이름조차 못 찾으면 기기가 끊긴 것으로 본다`() {
        assertEquals(
            TransportFailure.Offline,
            UnknownHostException("api.accentury.com").toTransportFailure(),
        )
    }

    @Test
    fun `연결을 거절당하면 서버 쪽 문제로 본다`() {
        assertEquals(
            TransportFailure.ServerUnreachable,
            ConnectException("Connection refused").toTransportFailure(),
        )
    }

    @Test
    fun `소켓 타임아웃은 지연으로 본다`() {
        assertEquals(
            TransportFailure.Timeout,
            SocketTimeoutException("read timed out").toTransportFailure(),
        )
    }

    /** OkHttp의 callTimeout(KAN-146)이 호출을 끊을 때 던지는 형. 상위 타입 하나로 함께 잡힌다. */
    @Test
    fun `callTimeout이 끊은 InterruptedIOException도 지연으로 본다`() {
        assertEquals(
            TransportFailure.Timeout,
            InterruptedIOException("timeout").toTransportFailure(),
        )
    }

    @Test
    fun `갈래에 없는 IOException은 원인을 짐작하지 않는다`() {
        assertEquals(
            TransportFailure.Unknown,
            IOException("unexpected end of stream").toTransportFailure(),
        )
    }
}
