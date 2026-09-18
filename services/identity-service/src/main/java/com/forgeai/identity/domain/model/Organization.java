package com.forgeai.identity.domain.model;

import com.forgeai.identity.domain.valueobject.OrganizationSlug;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class Organization {
    private UUID id;
    private String name;
    private OrganizationSlug slug;
}
