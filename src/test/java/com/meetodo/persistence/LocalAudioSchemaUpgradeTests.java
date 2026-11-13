package com.meetodo.persistence;

import com.meetodo.meeting.*;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=update","spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/local/audio-source-upgrade.sql"})
@ActiveProfiles("test")
class LocalAudioSchemaUpgradeTests {
    static final String URL="jdbc:h2:mem:local-audio-upgrade;DB_CLOSE_DELAY=-1;MODE=PostgreSQL";
    @DynamicPropertySource static void oldSchema(DynamicPropertyRegistry registry) throws Exception {
        try(var connection=DriverManager.getConnection(URL,"sa",""); var statement=connection.createStatement()) {
            ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration/V1__initial_schema.sql"));
            statement.execute("ALTER TABLE meetings ALTER COLUMN source ENUM('API_TEXT','SLACK_TEXT','SLACK_AUDIO')");
            statement.execute("INSERT INTO meetings(raw_text,content_hash,source,status,llm_call_count,tool_call_count,validation_failure_count,created_at) VALUES('기존 회의','legacy-source','API_TEXT','DONE',0,0,0,CURRENT_TIMESTAMP)");
        }
        registry.add("spring.datasource.url",()->URL);
    }
    @Autowired MeetingRepository meetings;
    // H2 시작 SQL이 기존 enum을 확장하고 Hibernate update 뒤에도 텍스트 행과 AUDIO 저장을 보존한다.
    @Test void upgradingExistingSourceEnumPreservesTextMeetingAndAcceptsAudio() {
        assertThat(meetings.findByContentHash("legacy-source").orElseThrow().getRawText()).isEqualTo("기존 회의");
        var audio=Meeting.create("전사된 새 회의",MeetingSource.AUDIO);
        audio.recordTranscription("test-transcribe",0L);
        Long id=meetings.saveAndFlush(audio).getId();
        assertThat(meetings.findById(id).orElseThrow().getSource()).isEqualTo(MeetingSource.AUDIO);
    }
}
