package com.forgeai.identity.application.service;

import com.forgeai.identity.application.port.out.SessionRepository;
import com.forgeai.identity.domain.model.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class SessionServiceTest {

    @Mock
    private SessionRepository sessionRepository;

    @InjectMocks
    private SessionService sessionService;

    @Test
    void createSession_Success() {
        when(sessionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        
        Session session = sessionService.createSession(UUID.randomUUID(), Instant.now().plusSeconds(3600), "meta");
        
        assertNotNull(session);
        verify(sessionRepository).save(any(Session.class));
    }

    @Test
    void revokeSession_Success() {
        Session session = new Session();
        session.setId(UUID.randomUUID());
        
        when(sessionRepository.findById(session.getId())).thenReturn(Optional.of(session));
        
        sessionService.revokeSession(session.getId());
        
        assertNotNull(session.getRevokedAt());
        verify(sessionRepository).save(session);
    }

    @Test
    void revokeAllUserSessions_Success() {
        Session s1 = new Session();
        Session s2 = new Session();
        UUID userId = UUID.randomUUID();
        
        when(sessionRepository.findActiveByUserId(userId)).thenReturn(List.of(s1, s2));
        
        sessionService.revokeAllUserSessions(userId);
        
        assertNotNull(s1.getRevokedAt());
        assertNotNull(s2.getRevokedAt());
        verify(sessionRepository, times(2)).save(any());
    }
}
