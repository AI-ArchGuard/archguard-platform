package io.github.aiarchguard.platform.project.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

record UpdateProjectRequest(
    @NotBlank @Size(max = 120) String name,
    @NotNull @PositiveOrZero Long version
) {}
