package ru.retail.service.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DromListingItem {

    @JsonProperty("bull_id")
    private Long bullId;

    private String title;
    private String description;

    private BigDecimal price;

    @JsonProperty("minimal_price")
    private BigDecimal minimalPrice;

    private String url;

    @JsonProperty("car_state")
    private String carState;

    private String location;

    @JsonProperty("dealer_name")
    private String dealerName;

    @JsonProperty("deal_type")
    private String dealType;

    @JsonProperty("seller_type")
    private String sellerType;

    @JsonProperty("images")
    private List<String> images;

    private Map<String, String> attributes;

    /**
     * Возвращает эффективную цену (минимальную, если указана)
     */
    public BigDecimal getEffectivePrice() {
        if (minimalPrice != null && minimalPrice.compareTo(BigDecimal.ZERO) > 0) {
            return minimalPrice;
        }
        return price;
    }
}
