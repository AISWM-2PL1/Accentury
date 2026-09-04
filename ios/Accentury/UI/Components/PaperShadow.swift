import SwiftUI

/// 오프셋 종이 그림자 (정본 §5, KAN-161 2단계). 안드로이드 `ui/components/PaperShadow.kt`의
/// 이식본이다. 오른쪽·아래로 ``Papercut/paperShadowX``·``Papercut/paperShadowY``만큼 어긋난
/// 자리에 단색 면 한 겹을 깐다 — 번지지도, 흐려지지도, 비쳐 보이지도 않는 그림자다.
///
/// SwiftUI의 `.shadow(radius:)`를 쓰지 않는 이유는 안드로이드가 Material `elevation`을 버린
/// 이유와 같다: 저쪽은 사방으로 번지는 흐린 그림자라 이 모양이 나오지 않는다. 알파를 섞은
/// 흐림은 Papercut에 없는 재질이다.
///
/// ## 왜 padding으로 자리를 먼저 비우는가
///
/// `background`만으로 그리면 그림자가 노드 바깥에 그려져 레이아웃이 그 자리를 모른다 —
/// 아래 요소가 그림자 위로 올라온다. 그래서 어긋난 만큼을 먼저 비우고(padding) 비운 자리에
/// 그림자를 그린다. 이 modifier를 붙인 요소가 차지하는 크기는 본체 + (3, 4)이고, 그 안에서
/// 본체는 왼쪽 위에 붙는다.
///
/// ## 눌림
///
/// 누르는 쪽은 이 modifier가 아니라 본체가 한다: 본체에 `offset(x: 3, y: 4)`를 주면 정확히
/// 그림자 자리로 내려가 그림자를 덮는다. 그래서 눌림 전후로 전체 크기가 같고, 옆 요소가
/// 밀려 화면이 들썩이지 않는다. 두 곳이 다른 값을 쓰면 종이가 바닥에 닿지 않고 어긋난 채로
/// 멈추므로, 양쪽 모두 ``Papercut``의 같은 토큰을 읽는다.
extension View {

    /// - Parameter visible: 그림자를 그릴지. `false`면 자리만 비운다 — 보조 버튼처럼 그림자가
    ///   없는 컨트롤도 주 버튼과 같은 크기·같은 눌림 거리를 갖게 하려는 것이다.
    ///
    /// `background`가 `padding`보다 **먼저** 붙는 순서가 규칙이다 — 배경은 그 시점의 콘텐츠
    /// 크기에 맞춰지므로, 순서를 뒤집으면 그늘이 비워 둔 여백까지 먹어 본체보다 3·4만큼
    /// 커진다(원형에서는 지름이 어긋나 테두리 밖으로 삐져나온다).
    func paperShadow(cornerRadius: CGFloat, visible: Bool = true) -> some View {
        background(alignment: .topLeading) {
            if visible {
                RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                    .fill(Papercut.paperShadow)
                    .offset(x: Papercut.paperShadowX, y: Papercut.paperShadowY)
            }
        }
        .padding(EdgeInsets(top: 0, leading: 0, bottom: Papercut.paperShadowY, trailing: Papercut.paperShadowX))
    }

    /// 원형용 ``paperShadow(cornerRadius:visible:)``. 반경을 값으로 받지 않고 `Circle()`에 맡기는
    /// 이유는 원형 요소의 지름이 화면마다 다를 수 있고, 반경이 지름의 절반에서 조금이라도
    /// 어긋나면 동그라미 뒤에서 각진 모서리가 삐져나오기 때문이다.
    func paperCircleShadow(visible: Bool = true) -> some View {
        background(alignment: .topLeading) {
            if visible {
                Circle()
                    .fill(Papercut.paperShadow)
                    .offset(x: Papercut.paperShadowX, y: Papercut.paperShadowY)
            }
        }
        .padding(EdgeInsets(top: 0, leading: 0, bottom: Papercut.paperShadowY, trailing: Papercut.paperShadowX))
    }
}
