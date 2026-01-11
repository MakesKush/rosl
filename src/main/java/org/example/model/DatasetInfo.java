package org.example.model;

import java.time.LocalDateTime;

public record DatasetInfo(
        long id,
        String name,
        int n,
        int d,
        LocalDateTime createdAt
) { }
