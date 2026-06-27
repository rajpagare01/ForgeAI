package com.forgeai.identity.domain.repository;

import java.util.Optional;
import com.forgeai.identity.domain.aggregate.User;
import com.forgeai.identity.domain.valueobject.Email;
import com.forgeai.identity.domain.valueobject.UserId;

public interface UserRepository {
    void save(User user);
    Optional<User> findById(UserId userId);
    Optional<User> findByEmail(Email email);
    boolean existsByEmail(Email email);
    void delete(UserId userId);
}
