package com.meetodo.rag.question;
import com.meetodo.rag.index.RagSourceSnapshot;
import java.util.List;
import org.springframework.ai.chat.model.ChatResponse;
public interface RagAnswerClient { ChatResponse answer(String question, List<RagSourceSnapshot> sources); }
