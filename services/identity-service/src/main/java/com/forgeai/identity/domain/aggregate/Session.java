package com.forgeai.identity.domain.aggregate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import com.forgeai.identity.domain.entity.RefreshToken;
import com.forgeai.identity.domain.event.DomainEvent;
import com.forgeai.identity.domain.event.SessionCreatedEvent;
import com.forgeai.identity.domain.event.SessionRevokedEvent;
import com.forgeai.identity.domain.exception.SessionExpiredException;
import com.forgeai.identity.domain.exception.SessionRevokedException;
import com.forgeai.identity.domain.valueobject.DeviceInfo;
import com.forgeai.identity.domain.valueobject.IpAddress;
import com.forgeai.identity.domain.valueobject.SessionId;
import com.forgeai.identity.domain.valueobject.UserAgent;
import com.forgeai.identity.domain.valueobject.UserId;

public class Session {
    private final SessionId sessionId;
    private final UserId userId;
    private final DeviceInfo deviceInfo;
    private final IpAddress ipAddress;
    private final UserAgent userAgent;
    private SessionStatus status;
    private final Instant createdAt;
    private Instant lastAccessed;
    private RefreshToken refreshToken;

    private final List<DomainEvent> domainEvents = new ArrayList<>();

    private Session(SessionId sessionId, UserId userId, DeviceInfo deviceInfo, IpAddress ipAddress, UserAgent userAgent, RefreshToken refreshToken) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.deviceInfo = deviceInfo;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.refreshToken = refreshToken;
        this.status = SessionStatus.ACTIVE;
        this.createdAt = Instant.now();
        this.lastAccessed = this.createdAt;
    }

    public static Session create(UserId userId, DeviceInfo deviceInfo, IpAddress ipAddress, UserAgent userAgent, RefreshToken initialToken) {
        SessionId newId = SessionId.generate();
        Session session = new Session(newId, userId, deviceInfo, ipAddress, userAgent, initialToken);
        session.addDomainEvent(new SessionCreatedEvent(newId.value(), userId.value()));
        return session;
    }

    public void revoke() {
        if (this.status == SessionStatus.REVOKED) {
            throw new SessionRevokedException("Session is already revoked.");
        }
        this.status = SessionStatus.REVOKED;
        if (!this.refreshToken.isRevoked()) {
            this.refreshToken.revoke();
        }
        this.addDomainEvent(new SessionRevokedEvent(this.sessionId.value(), this.userId.value()));
    }

    public void rotateRefreshToken(UUID newTokenId, String newTokenHash, Instant newExpiresAt) {
        if (this.status == SessionStatus.REVOKED) throw new SessionRevokedException("Cannot rotate token on revoked session.");
        if (this.status == SessionStatus.EXPIRED || this.refreshToken.isExpired()) {
            this.status = SessionStatus.EXPIRED;
            throw new SessionExpiredException("Cannot rotate token on expired session.");
        }
        
        try {
            this.refreshToken = this.refreshToken.rotate(newTokenId, newTokenHash, newExpiresAt);
            this.lastAccessed = Instant.now();
        } catch (Exception ex) {
            this.revoke(); // Suspicious activity triggers revocation
            throw ex;
        }
    }

    public SessionStatus getStatus() {
        if (status == SessionStatus.ACTIVE && refreshToken.isExpired()) {
            return SessionStatus.EXPIRED;
        }
        return status;
    }

    // Accessors
    public SessionId getSessionId() { return sessionId; }
    public UserId getUserId() { return userId; }
    public DeviceInfo getDeviceInfo() { return deviceInfo; }
    public IpAddress getIpAddress() { return ipAddress; }
    public UserAgent getUserAgent() { return userAgent; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastAccessed() { return lastAccessed; }
    public RefreshToken getRefreshToken() { return refreshToken; }

    // Event Management
    private void addDomainEvent(DomainEvent event) {
        this.domainEvents.add(event);
    }
    public List<DomainEvent> getDomainEvents() {
        return Collections.unmodifiableList(domainEvents);
    }
    public void clearDomainEvents() {
        this.domainEvents.clear();
    }
}
