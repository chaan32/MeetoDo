package com.meetodo.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class PostgresRegistrationMessageMigrationTests {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:0.8.7-pg16").asCompatibleSubstituteFor("postgres"));
    static JdbcTemplate jdbc;

    @BeforeAll static void migrate() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).load().migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        jdbc.update("INSERT INTO member_registration_requests(id,slack_user_id,pending_slack_user_id,family_name,given_name,team,status,duplicate_name,created_at) VALUES (7,'UAPPLICANT','UAPPLICANT','김','민수','팀','PENDING',false,now())");
    }

    @Test void adminCoordinatesAndFailureCodePersistPerRequest() {
        // 서로 다른 관리자에게 전송한 성공 위치와 실패 코드가 같은 신청에 독립적으로 저장된다.
        jdbc.update("INSERT INTO member_registration_messages(request_id,admin_slack_user_id,channel_id,message_ts,status) VALUES (7,'UONE','DONE','1.2','SENT')");
        jdbc.update("INSERT INTO member_registration_messages(request_id,admin_slack_user_id,status,error_code) VALUES (7,'UTWO','FAILED','missing_scope')");
        assertThat(jdbc.queryForMap("SELECT channel_id,message_ts,status,error_code FROM member_registration_messages WHERE admin_slack_user_id='UONE'"))
                .containsEntry("channel_id", "DONE").containsEntry("message_ts", "1.2").containsEntry("status", "SENT").containsEntry("error_code", null);
        assertThat(jdbc.queryForMap("SELECT channel_id,message_ts,status,error_code FROM member_registration_messages WHERE admin_slack_user_id='UTWO'"))
                .containsEntry("channel_id", null).containsEntry("message_ts", null).containsEntry("status", "FAILED").containsEntry("error_code", "missing_scope");
    }
    @Test void duplicateAdminMessageIsRejectedByDatabase() {
        // 같은 신청과 관리자 조합에 두 개의 DM 기록을 만들지 못한다.
        jdbc.update("INSERT INTO member_registration_messages(request_id,admin_slack_user_id,status) VALUES (7,'UDUP','FAILED')");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO member_registration_messages(request_id,admin_slack_user_id,status) VALUES (7,'UDUP','FAILED')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void unknownRequestCannotOwnMessage() {
        // 존재하지 않는 신청 ID에 대한 전송 기록은 FK가 거절한다.
        assertThatThrownBy(() -> jdbc.update("INSERT INTO member_registration_messages(request_id,admin_slack_user_id,status) VALUES (9999,'UINVALID','FAILED')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
