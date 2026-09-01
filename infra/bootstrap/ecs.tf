# ECS 서비스 연결 역할 (KAN-165). 계정에 1개뿐이라 bootstrap이 소유한다.
#
# ECS는 첫 클러스터를 만들 때 이 역할(AWSServiceRoleForECS)을 자동으로 만들려 하는데, 그 경로는 호출자에게
# iam:CreateServiceLinkedRole이 있어야 하고 실패 원인이 "클러스터 생성 실패"로만 보인다. 두 환경 스택이 각자
# 만들면 두 번째 apply가 EntityAlreadyExists로 실패한다 - OIDC 공급자와 같은 이유로 여기서 한 번만 만든다.
# 2026-09-02 기준 계정에 없었다 (get-role NoSuchEntity - 이 apply가 만들었다). 다른 경로로 이미 생긴 계정에서는
# apply가 "has been taken"으로 실패하므로 import한다: terraform import aws_iam_service_linked_role.ecs \
# arn:aws:iam::<계정>:role/aws-service-role/ecs.amazonaws.com/AWSServiceRoleForECS
resource "aws_iam_service_linked_role" "ecs" {
  aws_service_name = "ecs.amazonaws.com"

  # 지우면 두 환경의 ECS 서비스가 ALB 대상 등록과 ENI 관리를 잃는다.
  lifecycle {
    prevent_destroy = true
  }
}

output "ecs_service_linked_role_arn" {
  value       = aws_iam_service_linked_role.ecs.arn
  description = "envs/*의 fargate 모듈이 만드는 클러스터와 서비스가 쓰는 서비스 연결 역할"
}
