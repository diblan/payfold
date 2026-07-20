package com.blanchaert.billing.consumer.psp;

import com.blanchaert.billing.consumer.config.PaymentProviderProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.UUID;

@Component
public class PspClient {
    private final RestClient restClient;

    public PspClient(PaymentProviderProperties props) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(props.timeoutMs());
        requestFactory.setReadTimeout(props.timeoutMs());
        this.restClient = RestClient.builder()
                .baseUrl(props.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    public PspChargeOutcome charge(String idempotencyKey, UUID subscriptionId, long amountCents, String currency) {
        try {
            ChargeResponse response = restClient.post()
                    .uri("/psp/charges")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ChargeRequest(idempotencyKey, subscriptionId, amountCents, currency))
                    .retrieve()
                    .body(ChargeResponse.class);
            if (response != null && "succeeded".equals(response.status())) {
                return PspChargeOutcome.success();
            }
            if (response == null) {
                return PspChargeOutcome.failure("empty_provider_response");
            }
            return PspChargeOutcome.failure(response.reason() != null ? response.reason() : "declined");
        } catch (RestClientException exception) {
            // Timeouts, connection failures, and non-2xx all land here: a provider we
            // cannot get a definitive success from is a failed payment, not a message error.
            return PspChargeOutcome.failure("provider_error:" + exception.getClass().getSimpleName());
        }
    }

    record ChargeRequest(String idempotency_key, UUID subscription_id, long amount_cents, String currency) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChargeResponse(String status, String reason) {}
}
