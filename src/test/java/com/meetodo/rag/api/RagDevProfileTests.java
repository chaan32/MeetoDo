package com.meetodo.rag.api;
import com.meetodo.member.AdminAuthorization;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;
class RagDevProfileTests {
 ApplicationContextRunner context=new ApplicationContextRunner().withUserConfiguration(RagDevController.class)
  .withBean(com.fasterxml.jackson.databind.ObjectMapper.class,com.fasterxml.jackson.databind.ObjectMapper::new)
  .withBean(AdminAuthorization.class,()->new AdminAuthorization("UADMIN")).withBean(RagReindexJobs.class,RagReindexJobs::new);
 @Test void localExposesDevEndpoints() {
  // local 개발 환경은 별도 플래그 없이 노출한다.
  context.withPropertyValues("spring.profiles.active=local").run(c->assertThat(c).hasSingleBean(RagDevController.class));
 }
 @Test void dockerDefaultHidesDevEndpoints() {
  // Docker 기본 설정은 개발용 재색인을 숨긴다.
  context.withPropertyValues("spring.profiles.active=docker").run(c->assertThat(c).doesNotHaveBean(RagDevController.class));
 }
 @Test void dockerOptInExposesDevEndpoints() {
  // Docker에서 명시적으로 opt-in 하면 노출한다.
  context.withPropertyValues("spring.profiles.active=docker","meetodo.dev.endpoints-enabled=true").run(c->assertThat(c).hasSingleBean(RagDevController.class));
 }
 @Test void prodOptInStillHidesDevEndpoints() {
  // 운영 프로필에는 opt-in만으로 노출하지 않는다.
  context.withPropertyValues("spring.profiles.active=prod","meetodo.dev.endpoints-enabled=true").run(c->assertThat(c).doesNotHaveBean(RagDevController.class));
 }
 @Test void testProfileAlwaysHidesRealDevEndpoints() {
  // 테스트는 명시적 수동 컨트롤러 구성만 허용한다.
  context.withPropertyValues("spring.profiles.active=local,test").run(c->assertThat(c).doesNotHaveBean(RagDevController.class));
 }
}
