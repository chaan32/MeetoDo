package com.meetodo.persistence;

import com.meetodo.member.Member;
import com.meetodo.meeting.Meeting;
import com.meetodo.slack.SlackMessageReference;
import com.meetodo.task.Task;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class LocalReassignmentUpgradeTests {
    // 기존 채널 메시지는 로컬 보정 후에도 위치·업무·상태를 보존하고 기본 알림 종류를 받는다.
    @Test void legacyReferencePreservesLocationAndReceivesAssignmentDefault() throws Exception {
        String url = legacyDatabase();
        updateSchema(url);
        try (var connection = connect(url); var statement = connection.createStatement();
             var row = statement.executeQuery("SELECT * FROM slack_message_references WHERE id=1")) {
            assertThat(row.next()).isTrue();
            assertThat(row.getString("channel_id")).isEqualTo("CLEGACY");
            assertThat(row.getString("message_ts")).isEqualTo("7.000001");
            assertThat(row.getString("task_ids")).isEqualTo("[1]");
            assertThat(row.getString("message_type")).isEqualTo("CHANNEL");
            assertThat(row.getString("delivery_status")).isEqualTo("SENT");
            assertThat(row.getString("notification_kind")).isEqualTo("ASSIGNMENT");
            assertThat(row.getString("notification_actor_slack_user_id")).isNull();
        }
    }

    // 반복 보정은 기존 재지정·인계 알림 종류와 변경자 정보를 덮어쓰지 않는다.
    @Test void repeatedUpgradePreservesExplicitNotificationMetadata() throws Exception {
        String url = legacyDatabase();
        updateSchema(url);
        try (var connection = connect(url); var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE slack_message_references SET notification_kind='REASSIGNMENT', notification_actor_slack_user_id='UADMIN1' WHERE id=1");
            statement.executeUpdate("INSERT INTO slack_message_references(task_ids,message_type,target_slack_user_id,delivery_status,error_code,notification_kind,notification_actor_slack_user_id) VALUES ('[1]','DM','UPREV1','FAILED','missing_scope','HANDOFF','UADMIN1')");
        }
        updateSchema(url);
        updateSchema(url);
        try (var connection = connect(url); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT * FROM slack_message_references ORDER BY id")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString("notification_kind")).isEqualTo("REASSIGNMENT");
            assertThat(rows.getString("notification_actor_slack_user_id")).isEqualTo("UADMIN1");
            assertThat(rows.getString("channel_id")).isEqualTo("CLEGACY");
            assertThat(rows.getString("message_ts")).isEqualTo("7.000001");
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString("notification_kind")).isEqualTo("HANDOFF");
            assertThat(rows.getString("notification_actor_slack_user_id")).isEqualTo("UADMIN1");
            assertThat(rows.getString("target_slack_user_id")).isEqualTo("UPREV1");
            assertThat(rows.getString("delivery_status")).isEqualTo("FAILED");
            assertThat(rows.getString("error_code")).isEqualTo("missing_scope");
            assertThat(rows.getString("channel_id")).isNull();
            assertThat(rows.getString("message_ts")).isNull();
            assertThat(rows.next()).isFalse();
        }
    }

    // Hibernate update로 추가한 날짜 경고는 기존 업무에서 null이며 재시작 후 저장값을 유지한다.
    @Test void taskWarningColumnPreservesLegacyTaskAndSavedWarningAcrossRestarts() throws Exception {
        String url = legacyDatabase();
        updateSchema(url);
        try (var connection = connect(url); var statement = connection.createStatement();
             var row = statement.executeQuery("SELECT title,due_date_warning FROM tasks WHERE id=1")) {
            assertThat(row.next()).isTrue();
            assertThat(row.getString("title")).isEqualTo("기존 업무");
            assertThat(row.getString("due_date_warning")).isNull();
        }
        try (var connection = connect(url); var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE tasks SET due_date_warning='날짜 확인 필요' WHERE id=1");
        }
        updateSchema(url);
        try (var connection = connect(url); var statement = connection.createStatement();
             var row = statement.executeQuery("SELECT title,due_date_warning FROM tasks WHERE id=1")) {
            assertThat(row.next()).isTrue();
            assertThat(row.getString("title")).isEqualTo("기존 업무");
            assertThat(row.getString("due_date_warning")).isEqualTo("날짜 확인 필요");
        }
    }

    // 신규 로컬 DB에서는 보정 SQL이 테이블 부재를 허용하고 Hibernate가 모든 컬럼을 만든다.
    @Test void freshDatabaseStartsWithoutLegacyTables() throws Exception {
        String url = databaseUrl();
        updateSchema(url);
        updateSchema(url);
        try (var connection = connect(url); var statement = connection.createStatement()) {
            assertThat(statement.executeUpdate("INSERT INTO tasks(title,status,created_at,deleted,due_date_warning) VALUES ('새 업무','TODO',CURRENT_TIMESTAMP,false,'날짜 확인 필요')")).isEqualTo(1);
            assertThat(statement.executeUpdate("INSERT INTO slack_message_references(task_ids,message_type,delivery_status,notification_kind,notification_actor_slack_user_id) VALUES ('[1]','DM','FAILED','HANDOFF','UADMIN1')")).isEqualTo(1);
        }
    }

    private String legacyDatabase() throws Exception {
        String url = databaseUrl();
        try (var connection = connect(url); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE slack_message_references (id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, channel_id varchar(80) NOT NULL, message_ts varchar(80) NOT NULL, meeting_id bigint, digest_date date, task_ids text NOT NULL, CONSTRAINT uk_slack_message_channel_ts UNIQUE(channel_id,message_ts))");
            statement.executeUpdate("INSERT INTO slack_message_references(channel_id,message_ts,task_ids) VALUES ('CLEGACY','7.000001','[1]')");
            statement.execute("CREATE TABLE tasks (id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, title varchar(200) NOT NULL, status varchar(20) NOT NULL, created_at timestamp with time zone NOT NULL)");
            statement.executeUpdate("INSERT INTO tasks(title,status,created_at) VALUES ('기존 업무','TODO',CURRENT_TIMESTAMP)");
        }
        return url;
    }

    private void updateSchema(String url) throws Exception {
        try (var connection = connect(url)) {
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                    new org.springframework.core.io.ClassPathResource("db/local/slack-message-dm-upgrade.sql"));
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                    new org.springframework.core.io.ClassPathResource("db/local/reassignment-messages-upgrade.sql"));
        }
        var registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.connection.driver_class", "org.h2.Driver")
                .applySetting("hibernate.connection.url", url)
                .applySetting("hibernate.connection.username", "sa")
                .applySetting("hibernate.connection.password", "")
                .applySetting("hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
                .applySetting("hibernate.hbm2ddl.auto", "update")
                .applySetting("hibernate.hbm2ddl.halt_on_error", true).build();
        try (var ignored = new MetadataSources(registry).addAnnotatedClass(Meeting.class)
                .addAnnotatedClass(Member.class).addAnnotatedClass(Task.class)
                .addAnnotatedClass(SlackMessageReference.class).buildMetadata().buildSessionFactory()) {
            // 로컬과 같은 SQL 보정 → Hibernate update 순서를 실행한다.
        } finally { StandardServiceRegistryBuilder.destroy(registry); }
    }

    private String databaseUrl() { return "jdbc:h2:mem:reassignment-upgrade-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"; }
    private Connection connect(String url) throws Exception { return DriverManager.getConnection(url, "sa", ""); }
}
