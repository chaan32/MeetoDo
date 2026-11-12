# 7단계 Docker Compose + PostgreSQL

사용자 정책으로 승인 완료. local/test는 H2 유지, docker는 Flyway V1+Hibernate validate. 외부 비밀은 환경변수로만 전달. 실제 메신저·OpenAI는 검증 중 호출하지 않는다.

- [x] 5단계 사용자 Slack 실제 확인을 DEVLOG에 기록.
- [x] 실제 PostgreSQL Testcontainers에서 V1 누락 RED 확인 후 초기 스키마 구현.
- [x] OID LOB autocommit 조회 실패를 재현하고 text 매핑 수정 후 GREEN.
- [x] docker 프로필·Flyway 비활성 기본값·명시적 Docker 데모 API 및 알림 조건 구현.
- [x] 멀티 스테이지 JRE21·non-root·헬스체크·빌드 컨텍스트 허용 목록·Compose 앱2/DB볼륨 구현.
- [x] 전체 테스트·실제 이미지 빌드·두 컨테이너 헬스 및 동시 DB 선점/로컬 mock전송1회 확인.
- [x] 컨테이너 재생성 시 데이터 보존 확인 후 검증 컨테이너 종료.
- [x] README·DEVLOG·실행 안내·면접 문서 최종 기록.

사용자 H2 파일·기존 DB 볼륨을 삭제하거나 Git 커밋/푸시하지 않는다. 검증은 별도 meetodo-verification 프로젝트와 더미 비밀값을 사용한다.
