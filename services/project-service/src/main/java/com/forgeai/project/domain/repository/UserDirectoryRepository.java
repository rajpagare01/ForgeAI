package com.forgeai.project.domain.repository;

import com.forgeai.project.domain.model.UserDirectory;

import java.util.Optional;
import java.util.UUID;

public interface UserDirectoryRepository {
    UserDirectory save(UserDirectory userDirectory);
    Optional<UserDirectory> findById(UUID id);
    Optional<UserDirectory> findByEmail(String email);
}
