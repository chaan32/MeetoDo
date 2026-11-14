package com.meetodo.member.registration;

public record RegistrationView(Long id, String slackUserId, String familyName, String givenName, String team,
        RegistrationStatus status, String processedBySlackUserId, String rejectionReason, boolean duplicateName, Long memberId) { }
