package com.meetodo.persistence;

import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import static org.assertj.core.api.Assertions.assertThat;

class LocalTaskUpgradeTests {
    // 기존 H2 행을 유지하고 재실행해도 삭제 상태 기본값이 false로 보존된다.
    @Test void preservesExistingTasksWithNonDeletedDefaultAcrossRepeatedUpgrade() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:task-upgrade-" + UUID.randomUUID(), "sa", "");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE tasks(id bigint PRIMARY KEY,title varchar(200))");
            statement.execute("INSERT INTO tasks(id,title) VALUES(1,'기존 업무')");
            var script = new ClassPathResource("db/local/task-management-upgrade.sql");
            ScriptUtils.executeSqlScript(connection, script);
            ScriptUtils.executeSqlScript(connection, script);
            try (var rows = statement.executeQuery("SELECT title,deleted FROM tasks WHERE id=1")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("기존 업무");
                assertThat(rows.getBoolean(2)).isFalse();
                assertThat(rows.next()).isFalse();
            }
        }
    }
}
