package com.forgeai.identity.infrastructure;

import com.forgeai.identity.domain.aggregate.User;
import com.forgeai.identity.domain.valueobject.Email;
import com.forgeai.identity.infrastructure.persistence.repository.UserRepositoryImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
public class UserRepositoryIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private UserRepositoryImpl userRepository;

    @Test
    void testSaveAndFindUser() {
        User user = User.register(new Email("test@forgeai.com"), "hashed_password");
        userRepository.save(user);

        User retrieved = userRepository.findById(user.getId()).orElse(null);
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.getEmail().value()).isEqualTo("test@forgeai.com");
    }
}
