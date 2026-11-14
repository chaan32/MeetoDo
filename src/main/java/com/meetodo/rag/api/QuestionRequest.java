package com.meetodo.rag.api;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
public record QuestionRequest(@NotBlank @Size(max=2000) String question, boolean diagnostic) { }
