package com.accentury.app.net

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.UnknownHostException

/**
 * 응답이 오지 않은 전송 실패를, 사용자에게 서로 다른 말을 해줄 수 있는 만큼만 갈라놓은 것.
 *
 * 한계를 먼저 적어둔다: 응답이 없는 실패라 서버가 무슨 일을 겪었는지는 알 방법이 없다. 여기서
 * 가르는 것은 정확한 원인이 아니라 **누구 쪽에서 끊겼는지** 정도다 - 기기 쪽([Offline]), 서버
 * 쪽([ServerUnreachable]), 어느 쪽인지 말할 수 없는 지연([Timeout])과 나머지([Unknown]).
 * 예외 종류가 주는 힌트가 거기까지고, 그 이상을 문구로 단정하면 틀린 안내가 된다.
 *
 * [android.net.ConnectivityManager]는 일부러 쓰지 않는다. 안 그래도 알 수 있는 구분을 얻자고
 * 네트워크 클라이언트에 안드로이드 프레임워크를 끌어들이면 JVM 단위 테스트에서 이 판정을 돌릴
 * 수 없게 된다. 연결이 살아 있는데 요청이 못 나가는 경우(캡티브 포털 등)도 있어서, 시스템에
 * 물어본 답이 더 정확하다는 보장도 없다.
 */
enum class TransportFailure {

    /** 이름조차 못 찾았다. 기기가 망에 못 붙어 있을 때 나오는 전형적인 모습이다. */
    Offline,

    /** 주소는 찾았는데 연결을 거절당했다. 망은 살아 있고 서버 쪽이 안 받는 상태다. */
    ServerUnreachable,

    /** 붙긴 했는데 정해둔 시간 안에 끝나지 않았다. 느린 망인지 느린 서버인지는 가릴 수 없다. */
    Timeout,

    /** 위 어디에도 안 들어가는 I/O 실패. 원인을 짐작해 말하지 않는다. */
    Unknown,
}

/**
 * 전송 중 터진 [IOException]을 [TransportFailure]로 옮긴다.
 *
 * [java.net.SocketTimeoutException]은 [InterruptedIOException]의 하위라 따로 잡지 않는다.
 * OkHttp의 callTimeout이 끊을 때 던지는 것도 [InterruptedIOException]이라 둘이 한 갈래로 모인다.
 */
fun IOException.toTransportFailure(): TransportFailure = when (this) {
    is UnknownHostException -> TransportFailure.Offline
    is ConnectException -> TransportFailure.ServerUnreachable
    is InterruptedIOException -> TransportFailure.Timeout
    else -> TransportFailure.Unknown
}
