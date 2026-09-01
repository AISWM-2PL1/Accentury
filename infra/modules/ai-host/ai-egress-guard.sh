#!/bin/bash
# ai 컨테이너의 인터넷과 IMDS 차단 (KAN-36). systemd accentury-egress-guard.service가 docker 뒤,
# accentury.service 앞에 부른다 - 규칙이 없으면 compose도 뜨지 않는다.
#
# 같은 호스트 시절(KAN-129)에는 ai를 게이트웨이 없는 internal 네트워크에 붙여 끊었다. 전용
# 호스트에서는 컨테이너가 8000을 발행해 backend 호스트를 받아야 하므로 internal 네트워크로는
# 안 되고, 보안 그룹 egress는 호스트와 컨테이너를 못 가른다(호스트는 ECR, SSM, Route 53,
# CloudWatch에 나가야 한다). 그래서 호스트 iptables의 DOCKER-USER 체인에 건다 - docker가 만들고
# 비우지 않는 사용자 체인이라 docker 재시작에도 남고, FORWARD 경로에서 docker 규칙보다 먼저 본다.
#
# 규칙: 컨테이너 브리지에서 시작되는(NEW) 연결 중 목적지가 VPC 대역 밖이면 버린다. backend가 거는
# 8000 연결의 응답은 ESTABLISHED라 걸리지 않는다. IMDS(169.254.169.254)는 VPC 대역 밖이라 이미
# 걸리지만, 규칙 하나가 빠져도 막히게 따로 한 줄 더 둔다 (시작 템플릿의 hop limit 1과 겹치는 세 번째 겹).
# IPv6는 VPC에 없고 docker 브리지도 IPv6를 켜지 않는다.
set -euo pipefail

# shellcheck source=/dev/null
. /etc/accentury/env.conf

# 멱등 - 이미 있는 규칙은 다시 넣지 않는다 (reload, 재실행 대비).
ensure() {
  iptables -C DOCKER-USER "$@" 2>/dev/null || iptables -I DOCKER-USER "$@"
}

# docker0(기본 브리지)과 br-*(compose가 만드는 사용자 브리지) 양쪽.
for bridge in docker0 'br+'; do
  ensure -i "$bridge" ! -d "$VPC_CIDR" -m conntrack --ctstate NEW -j DROP
  ensure -i "$bridge" -d 169.254.169.254 -j DROP
done

echo "DOCKER-USER egress guard 적용 (허용 대역 $VPC_CIDR):"
iptables -S DOCKER-USER
