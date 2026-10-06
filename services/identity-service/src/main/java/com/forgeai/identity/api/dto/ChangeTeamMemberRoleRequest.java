package com.forgeai.identity.api.dto;

import lombok.Data;
import java.util.UUID;

@Data
public class ChangeTeamMemberRoleRequest {
    private UUID roleId;
}
