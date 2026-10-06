package com.forgeai.identity.api.dto;

import lombok.Data;
import java.util.UUID;

@Data
public class AddTeamMemberRequest {
    private UUID userId;
    private UUID roleId;
}
