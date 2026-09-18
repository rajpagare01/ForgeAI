package com.forgeai.identity.application.port.out;

import com.forgeai.identity.domain.model.User;
import com.forgeai.identity.domain.valueobject.Email;
import com.forgeai.identity.domain.valueobject.Username;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository {
    Optional<User> findById(UUID id);
    Optional<User> findByEmail(Email email);
    Optional<User> findByUsername(Username username);
    User save(User user);
}
