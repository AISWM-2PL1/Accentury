provider "aws" {
  region = var.region

  # 모든 생성 리소스에 env 태그를 일괄 적용한다 (KAN-140, KAN-35 비용 태그 AC).
  default_tags {
    tags = {
      env        = var.env
      project    = "accentury"
      managed-by = "terraform"
    }
  }
}

# CloudFront는 us-east-1 인증서만 읽는다 (KAN-119). 인증서 data 조회 전용.
provider "aws" {
  alias  = "us_east_1"
  region = "us-east-1"

  default_tags {
    tags = {
      env        = var.env
      project    = "accentury"
      managed-by = "terraform"
    }
  }
}
