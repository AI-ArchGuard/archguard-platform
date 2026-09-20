package io.github.aiarchguard.platform.project.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

record CreateProjectRequest(
    @NotBlank @Pattern(regexp = "^[a-z][a-z0-9-]{2,62}$") String key,
    @NotBlank @Size(max = 120) String name) {}
