output "parameter_names" {
  value = [
    aws_ssm_parameter.spring_profiles_active.name,
    aws_ssm_parameter.ai_base_url.name,
    aws_ssm_parameter.datasource_url.name,
    aws_ssm_parameter.trusted_proxies.name,
    aws_ssm_parameter.web_test_url.name,
    aws_ssm_parameter.admin_token.name,
  ]
  description = "이 모듈이 만드는 SSM 파라미터 이름. compute 모듈이 인스턴스 생성 순서를 잡는 데 쓴다. IMAGE_TAG(KAN-128)는 여기 없다."
}
