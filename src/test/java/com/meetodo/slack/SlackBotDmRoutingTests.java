package com.meetodo.slack;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetodo.meeting.*;
import com.meetodo.task.*;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.request.chat.*;
import com.slack.api.methods.response.chat.ChatPostMessageResponse;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SlackBotDmRoutingTests {
    private final MethodsClient client = mock(MethodsClient.class);
    private final SlackBotService bot = new SlackBotService(client, "CTEAM", mock(SlackMessageReferenceRepository.class),
            mock(TaskRepository.class), mock(TaskService.class), mock(MeetingResults.class), mock(MeetingRepository.class),
            new ObjectMapper(), mock(org.springframework.transaction.PlatformTransactionManager.class));

    @Test void dmProgressReservesTeamChannelBeforeMeetingPersistence() throws Exception {
        // DM 진행 좌표와 팀 채널 결과 좌표는 별개로 보존한다.
        var posted = new ChatPostMessageResponse(); posted.setOk(true); posted.setChannel("CTEAM"); posted.setTs("2.0");
        when(client.chatPostMessage(any(ChatPostMessageRequest.class))).thenReturn(posted);
        var publication = bot.prepareInput("DORIGIN", "1.0");
        assertThat(publication.inputChannel()).isEqualTo("DORIGIN");
        assertThat(publication.inputTs()).isEqualTo("1.0");
        assertThat(publication.resultChannel()).isEqualTo("CTEAM");
        assertThat(publication.resultTs()).isEqualTo("2.0");
        assertThat(publication.routed()).isTrue();
    }

    @Test void channelInputKeepsOriginalThreadMessageWithoutPostingElsewhere() {
        // 채널 멘션의 진행 위치는 그대로 결과 위치로 사용한다.
        var publication = bot.prepareInput("CORIGIN", "1.0");
        assertThat(publication.resultChannel()).isEqualTo("CORIGIN");
        assertThat(publication.resultTs()).isEqualTo("1.0");
        assertThat(publication.routed()).isFalse();
        verifyNoInteractions(client);
    }
}
