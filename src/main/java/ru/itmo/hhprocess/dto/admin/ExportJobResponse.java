package ru.itmo.hhprocess.dto.admin;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class ExportJobResponse {
    int scheduledCount;
}
