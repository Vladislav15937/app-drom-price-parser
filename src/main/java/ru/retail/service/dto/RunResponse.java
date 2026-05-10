package ru.retail.service.dto;

import lombok.Data;

import java.time.Instant;
import java.util.Map;

@Data
public class RunResponse {
    private Data data;

    @lombok.Data
    public static class Data {
        private String id;
        private String actId;
        private String status;
        private Instant startedAt;
        private Instant finishedAt;
        private Map<String, Object> stats;
        private String defaultDatasetId;
    }
}
