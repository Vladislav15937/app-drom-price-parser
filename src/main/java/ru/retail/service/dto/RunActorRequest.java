package ru.retail.service.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RunActorRequest {

    @JsonProperty("startUrls")
    private List<Map<String, String>> startUrls;

    @JsonProperty("maxItems")
    private int maxItems;

    @JsonProperty("extendOutputFunction")
    @Builder.Default
    private String extendOutputFunction = "($) => { return $; }";

    @JsonProperty("proxyConfiguration")
    private ProxyConfiguration proxyConfiguration;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProxyConfiguration {
        @JsonProperty("useApifyProxy")
        @Builder.Default
        private boolean useApifyProxy = true;

        @JsonProperty("apifyProxyGroups")
        private List<String> apifyProxyGroups;
    }

    @JsonProperty("maxRequestRetries")
    @Builder.Default
    private int maxRequestRetries = 2;

    @JsonProperty("maxConcurrency")
    @Builder.Default
    private int maxConcurrency = 5;
}