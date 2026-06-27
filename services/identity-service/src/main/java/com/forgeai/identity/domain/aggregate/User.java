package com.forgeai.identity.domain.aggregate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import com.forgeai.identity.domain.event.DomainEvent;
import com.forgeai.identity.domain.event.UserRegisteredEvent;
import com.forgeai.identity.domain.event.UserVerifiedEvent;
import com.forgeai.identity.domain.event.PasswordChangedEvent;
import com.forgeai.identity.domain.event.AccountSuspendedEvent;
import com.forgeai.identity.domain.event.AccountLockedEvent;
import com.forgeai.identity.domain.event.AccountUnlockedEvent;
import com.forgeai.identity.domain.exception.DomainException;
import com.forgeai.identity.domain.exception.AccountSuspendedException;
import com.forgeai.identity.domain.exception.AccountLockedException;
import com.forgeai.identity.domain.valueobject.Email;
import com.forgeai.identity.domain.valueobject.PasswordHash;
import com.forgeai.identity.domain.valueobject.UserId;

public class User {
    private final UserId userId;
    private final Email email;
    private PasswordHash passwordHash;
    private UserStatus status;
    private boolean mfaEnabled;
    private final Instant createdAt;
    private Instant updatedAt;
    
    private final List<DomainEvent> domainEvents = new ArrayList<>();

    private User(UserId userId, Email email, PasswordHash passwordHash) {
        this.userId = userId;
        this.email = email;
        this.passwordHash = passwordHash;
        this.status = UserStatus.PENDING_VERIFICATION;
        this.mfaEnabled = false;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public static User register(Email email, PasswordHash passwordHash) {
        UserId newId = UserId.generate();
        User user = new User(newId, email, passwordHash);
        user.addDomainEvent(new UserRegisteredEvent(newId.value()));
        return user;
    }

    public void verifyEmail() {
        if (this.status != UserStatus.PENDING_VERIFICATION) {
            throw new DomainException("Account is already verified or in invalid state.");
        }
        this.status = UserStatus.ACTIVE;
        this.updatedAt = Instant.now();
        this.addDomainEvent(new UserVerifiedEvent(this.userId.value()));
    }

    public void changePassword(PasswordHash newPasswordHash) {
        if (this.status == UserStatus.LOCKED) {
            throw new AccountLockedException("Cannot change password on a locked account.");
        }
        this.passwordHash = newPasswordHash;
        this.updatedAt = Instant.now();
        this.addDomainEvent(new PasswordChangedEvent(this.userId.value()));
    }

    public void suspendAccount() {
        if (this.status == UserStatus.SUSPENDED) {
            throw new DomainException("Account is already suspended.");
        }
        this.status = UserStatus.SUSPENDED;
        this.updatedAt = Instant.now();
        this.addDomainEvent(new AccountSuspendedEvent(this.userId.value()));
    }

    public void activateAccount() {
        if (this.status == UserStatus.ACTIVE) {
            throw new DomainException("Account is already active.");
        }
        this.status = UserStatus.ACTIVE;
        this.updatedAt = Instant.now();
        // Fire activated event if needed
    }

    public void lockAccount() {
        if (this.status == UserStatus.LOCKED) {
            throw new DomainException("Account is already locked.");
        }
        this.status = UserStatus.LOCKED;
        this.updatedAt = Instant.now();
        this.addDomainEvent(new AccountLockedEvent(this.userId.value()));
    }

    public void unlockAccount() {
        if (this.status != UserStatus.LOCKED) {
            throw new DomainException("Account is not locked.");
        }
        this.status = UserStatus.ACTIVE;
        this.updatedAt = Instant.now();
        this.addDomainEvent(new AccountUnlockedEvent(this.userId.value()));
    }

    public void assertCanLogin() {
        if (this.status == UserStatus.SUSPENDED) throw new AccountSuspendedException("Account is suspended.");
        if (this.status == UserStatus.LOCKED) throw new AccountLockedException("Account is locked.");
        if (this.status == UserStatus.PENDING_VERIFICATION) throw new DomainException("Email is not verified.");
    }

    // Accessors
    public UserId getUserId() { return userId; }
    public Email getEmail() { return email; }
    public PasswordHash getPasswordHash() { return passwordHash; }
    public UserStatus getStatus() { return status; }
    public boolean isMfaEnabled() { return mfaEnabled; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

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
