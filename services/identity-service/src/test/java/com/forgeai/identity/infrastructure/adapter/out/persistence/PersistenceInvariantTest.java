package com.forgeai.identity.infrastructure.adapter.out.persistence;

import com.forgeai.identity.infrastructure.adapter.out.persistence.entity.*;
import com.forgeai.identity.infrastructure.adapter.out.persistence.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Persistence invariant tests.
 *
 * Spins up a real PostgreSQL via Testcontainers, runs all Flyway migrations
 * (V1 + V2), and verifies key schema invariants.
 */
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PersistenceInvariantTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository orgRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private TeamMembershipRepository teamMembershipRepository;
    @Autowired private SecurityEventRepository securityEventRepository;
    @Autowired private SessionRepository sessionRepository;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private OrganizationMembershipRepository organizationMembershipRepository;

    // -----------------------------------------------------------------------
    // User — email uniqueness (case-insensitive)
    // -----------------------------------------------------------------------
    @Test
    void emailUniqueness_caseSensitiveSave_passes() {
        UserJpaEntity u = user("Alice@Example.com", "aliceuser");
        UserJpaEntity saved = userRepository.saveAndFlush(u);
        assertThat(saved.getId()).isNotNull();
    }

    @Test
    void emailUniqueness_duplicateLower_fails() {
        userRepository.saveAndFlush(user("SAME@example.com", "usr_one"));
        assertThatThrownBy(() -> userRepository.saveAndFlush(user("same@example.com", "usr_two")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void emailUniqueness_mixedCase_fails() {
        userRepository.saveAndFlush(user("User@Example.com", "userfoo"));
        assertThatThrownBy(() -> userRepository.saveAndFlush(user("user@EXAMPLE.com", "userbar")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // -----------------------------------------------------------------------
    // User — username uniqueness (case-insensitive)
    // -----------------------------------------------------------------------
    @Test
    void usernameUniqueness_duplicateLower_fails() {
        userRepository.saveAndFlush(user("alpha1@example.com", "MyUser"));
        assertThatThrownBy(() -> userRepository.saveAndFlush(user("alpha2@example.com", "myuser")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void usernameUniqueness_differentCase_fails() {
        userRepository.saveAndFlush(user("beta1@example.com", "HelloWorld"));
        assertThatThrownBy(() -> userRepository.saveAndFlush(user("beta2@example.com", "HELLOWORLD")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // -----------------------------------------------------------------------
    // Organization — slug uniqueness
    // -----------------------------------------------------------------------
    @Test
    void orgSlug_duplicate_fails() {
        orgRepository.saveAndFlush(org("Acme Corp", "acme"));
        assertThatThrownBy(() -> orgRepository.saveAndFlush(org("Another Corp", "acme")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // -----------------------------------------------------------------------
    // Team — name unique within org, allowed globally
    // -----------------------------------------------------------------------
    @Test
    void teamName_sameNameDifferentOrg_passes() {
        OrganizationJpaEntity org1 = orgRepository.saveAndFlush(org("Org One", "org-one"));
        OrganizationJpaEntity org2 = orgRepository.saveAndFlush(org("Org Two", "org-two"));
        teamRepository.saveAndFlush(team(org1.getId(), "backend"));
        // Same name, different org — must succeed
        TeamJpaEntity t2 = teamRepository.saveAndFlush(team(org2.getId(), "backend"));
        assertThat(t2.getId()).isNotNull();
    }

    @Test
    void teamName_duplicateWithinOrg_fails() {
        OrganizationJpaEntity org = orgRepository.saveAndFlush(org("Dup Org", "dup-org"));
        teamRepository.saveAndFlush(team(org.getId(), "alpha"));
        assertThatThrownBy(() -> teamRepository.saveAndFlush(team(org.getId(), "alpha")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // -----------------------------------------------------------------------
    // OrganizationMembership — unique(user_id, organization_id)
    // -----------------------------------------------------------------------
    @Test
    void orgMembership_duplicate_fails() {
        UserJpaEntity user = userRepository.saveAndFlush(user("om@example.com", "omuser"));
        OrganizationJpaEntity org = orgRepository.saveAndFlush(org("OM Org", "om-org"));
        RoleJpaEntity role = roleRepository.saveAndFlush(systemRole("MEMBER"));
        orgMembershipRepository().saveAndFlush(orgMembership(user.getId(), org.getId(), role.getId()));
        assertThatThrownBy(() ->
                orgMembershipRepository().saveAndFlush(orgMembership(user.getId(), org.getId(), role.getId())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // -----------------------------------------------------------------------
    // TeamMembership — unique(team_id, user_id) and nullable role_id
    // -----------------------------------------------------------------------
    @Test
    void teamMembership_duplicate_fails() {
        UserJpaEntity user = userRepository.saveAndFlush(user("tm@example.com", "tmuser"));
        OrganizationJpaEntity org = orgRepository.saveAndFlush(org("TM Org", "tm-org"));
        TeamJpaEntity team = teamRepository.saveAndFlush(team(org.getId(), "tm-team"));
        teamMembershipRepository.saveAndFlush(teamMembership(team.getId(), user.getId(), null));
        assertThatThrownBy(() ->
                teamMembershipRepository.saveAndFlush(teamMembership(team.getId(), user.getId(), null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void teamMembership_nullableRoleId_passes() {
        UserJpaEntity user = userRepository.saveAndFlush(user("nrt@example.com", "nrtuser"));
        OrganizationJpaEntity org = orgRepository.saveAndFlush(org("NRT Org", "nrt-org"));
        TeamJpaEntity team = teamRepository.saveAndFlush(team(org.getId(), "nrt-team"));
        TeamMembershipJpaEntity tm = teamMembershipRepository.saveAndFlush(
                teamMembership(team.getId(), user.getId(), null));
        assertThat(tm.getId()).isNotNull();
        assertThat(tm.getRoleId()).isNull();
    }

    // -----------------------------------------------------------------------
    // Role — system role uniqueness (partial index on name WHERE org_id IS NULL)
    // -----------------------------------------------------------------------
    @Test
    void systemRole_duplicateName_fails() {
        roleRepository.saveAndFlush(systemRole("OWNER"));
        assertThatThrownBy(() -> roleRepository.saveAndFlush(systemRole("OWNER")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void orgRole_sameNameDifferentOrg_passes() {
        OrganizationJpaEntity org1 = orgRepository.saveAndFlush(org("R Org1", "r-org1"));
        OrganizationJpaEntity org2 = orgRepository.saveAndFlush(org("R Org2", "r-org2"));
        roleRepository.saveAndFlush(orgRole("LEAD", org1.getId()));
        RoleJpaEntity r2 = roleRepository.saveAndFlush(orgRole("LEAD", org2.getId()));
        assertThat(r2.getId()).isNotNull();
    }

    @Test
    void orgRole_duplicateNameSameOrg_fails() {
        OrganizationJpaEntity org = orgRepository.saveAndFlush(org("Dup Role Org", "dup-role-org"));
        roleRepository.saveAndFlush(orgRole("CUSTOM", org.getId()));
        assertThatThrownBy(() -> roleRepository.saveAndFlush(orgRole("CUSTOM", org.getId())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // -----------------------------------------------------------------------
    // SecurityEvent — JSONB details
    // -----------------------------------------------------------------------
    @Test
    void securityEvent_jsonbDetails_roundTrip() {
        SecurityEventJpaEntity event = new SecurityEventJpaEntity();
        event.setEventType("LOGIN_FAILED");
        event.setCreatedAt(Instant.now());
        event.setDetails("{\"reason\":\"bad_password\",\"attempts\":3}");
        SecurityEventJpaEntity saved = securityEventRepository.saveAndFlush(event);
        SecurityEventJpaEntity fetched = securityEventRepository.findById(saved.getId()).orElseThrow();
        assertThat(fetched.getDetails()).contains("bad_password");
    }

    @Test
    void securityEvent_nullDetails_passes() {
        SecurityEventJpaEntity event = new SecurityEventJpaEntity();
        event.setEventType("USER_REGISTERED");
        event.setCreatedAt(Instant.now());
        SecurityEventJpaEntity saved = securityEventRepository.saveAndFlush(event);
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getDetails()).isNull();
    }

    // -----------------------------------------------------------------------
    // Session
    // -----------------------------------------------------------------------
    @Test
    void session_persist_passes() {
        UserJpaEntity user = userRepository.saveAndFlush(user("sess@example.com", "sessuser"));
        SessionJpaEntity s = new SessionJpaEntity();
        s.setUserId(user.getId());
        s.setCreatedAt(Instant.now());
        s.setLastUsedAt(Instant.now());
        s.setExpiresAt(Instant.now().plusSeconds(3600));
        SessionJpaEntity saved = sessionRepository.saveAndFlush(s);
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getRevokedAt()).isNull();
    }

    // -----------------------------------------------------------------------
    // RefreshToken — token_hash storage
    // -----------------------------------------------------------------------
    @Test
    void refreshToken_hashPersist_passes() {
        UserJpaEntity user = userRepository.saveAndFlush(user("rt@example.com", "rtuser"));
        SessionJpaEntity session = sessionRepository.saveAndFlush(session(user.getId()));
        RefreshTokenJpaEntity rt = new RefreshTokenJpaEntity();
        rt.setSessionId(session.getId());
        // SHA-256 produces 64 hex chars
        rt.setTokenHash("a".repeat(64));
        rt.setIssuedAt(Instant.now());
        rt.setExpiresAt(Instant.now().plusSeconds(86400));
        RefreshTokenJpaEntity saved = refreshTokenRepository.saveAndFlush(rt);
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getReplacedBy()).isNull();
        assertThat(saved.getTokenHash()).hasSize(64);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private OrganizationMembershipRepository orgMembershipRepository() {
        return organizationMembershipRepository;
    }

    private UserJpaEntity user(String email, String username) {
        UserJpaEntity u = new UserJpaEntity();
        u.setEmail(email);
        u.setUsername(username);
        u.setPasswordHash("$2a$10$hash");
        u.setStatus("ACTIVE");
        u.setCreatedAt(Instant.now());
        u.setUpdatedAt(Instant.now());
        return u;
    }

    private OrganizationJpaEntity org(String name, String slug) {
        OrganizationJpaEntity o = new OrganizationJpaEntity();
        o.setName(name);
        o.setSlug(slug);
        o.setStatus("ACTIVE");
        o.setCreatedAt(Instant.now());
        o.setUpdatedAt(Instant.now());
        return o;
    }

    private TeamJpaEntity team(UUID orgId, String name) {
        TeamJpaEntity t = new TeamJpaEntity();
        t.setOrganizationId(orgId);
        t.setName(name);
        t.setCreatedAt(Instant.now());
        t.setUpdatedAt(Instant.now());
        return t;
    }

    private RoleJpaEntity systemRole(String name) {
        RoleJpaEntity r = new RoleJpaEntity();
        r.setName(name);
        r.setScope("ORGANIZATION");
        r.setSystemDefined(true);
        r.setCreatedAt(Instant.now());
        r.setUpdatedAt(Instant.now());
        return r;
    }

    private RoleJpaEntity orgRole(String name, UUID orgId) {
        RoleJpaEntity r = new RoleJpaEntity();
        r.setName(name);
        r.setOrganizationId(orgId);
        r.setScope("ORGANIZATION");
        r.setSystemDefined(false);
        r.setCreatedAt(Instant.now());
        r.setUpdatedAt(Instant.now());
        return r;
    }

    private OrganizationMembershipJpaEntity orgMembership(UUID userId, UUID orgId, UUID roleId) {
        OrganizationMembershipJpaEntity m = new OrganizationMembershipJpaEntity();
        m.setUserId(userId);
        m.setOrganizationId(orgId);
        m.setRoleId(roleId);
        m.setStatus("ACTIVE");
        m.setCreatedAt(Instant.now());
        m.setUpdatedAt(Instant.now());
        return m;
    }

    private TeamMembershipJpaEntity teamMembership(UUID teamId, UUID userId, UUID roleId) {
        TeamMembershipJpaEntity m = new TeamMembershipJpaEntity();
        m.setTeamId(teamId);
        m.setUserId(userId);
        m.setRoleId(roleId); // nullable
        m.setStatus("ACTIVE");
        m.setCreatedAt(Instant.now());
        m.setUpdatedAt(Instant.now());
        return m;
    }

    private SessionJpaEntity session(UUID userId) {
        SessionJpaEntity s = new SessionJpaEntity();
        s.setUserId(userId);
        s.setCreatedAt(Instant.now());
        s.setLastUsedAt(Instant.now());
        s.setExpiresAt(Instant.now().plusSeconds(3600));
        return s;
    }
}
