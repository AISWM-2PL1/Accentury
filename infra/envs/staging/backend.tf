# 원격 state (KAN-140). 버킷은 infra/bootstrap이 만든다.
# backend 블록은 변수를 못 쓰므로 버킷 이름과 key가 리터럴이다 - 환경 간
# 파일 차이는 이 key와 terraform.tfvars뿐이어야 한다.
terraform {
  backend "s3" {
    bucket = "accentury-tfstate-325771561913"
    key    = "envs/staging/terraform.tfstate"
    region = "ap-northeast-2"
    # Terraform 1.10+의 S3 네이티브 잠금. 설치 버전 1.15.8에서 지원 확인
    # (2026-08-24) - DynamoDB 잠금 테이블이 필요 없다.
    use_lockfile = true
    encrypt      = true
  }
}
