package com.blanchaert.billing.consumer.bank;

import com.blanchaert.billing.consumer.config.BankProperties;
import com.blanchaert.billing.consumer.config.BankRegistry;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class BankClient {
    private final BankRegistry bankRegistry;
    private final Map<String, RestClient> clients;

    public BankClient(BankProperties props, BankRegistry bankRegistry) {
        this.bankRegistry = bankRegistry;
        Map<String, RestClient> configuredClients = new HashMap<>();
        for (BankProperties.BankEntry entry : bankRegistry.entries()) {
            SimpleClientHttpRequestFactory requestFactory =
                    new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(props.timeoutMs());
            requestFactory.setReadTimeout(props.timeoutMs());
            configuredClients.put(entry.id(), RestClient.builder()
                    .baseUrl(entry.baseUrl())
                    .requestFactory(requestFactory)
                    .build());
        }
        this.clients = Map.copyOf(configuredClients);
    }

    public void submitCollection(String bankId, String collectionId, long amountCents,
                                 String currency, String debtorIban,
                                 String mandateReference, String dueDate) {
        if (bankRegistry.byId(bankId) == null) {
            throw new IllegalStateException("unknown bank id " + bankId);
        }
        RestClient restClient = clients.get(bankId);
        try {
            restClient.post()
                    .uri("/collections")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new CollectionSubmission(collectionId, amountCents, currency,
                            debtorIban, mandateReference, dueDate))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException exception) {
            throw new BankSubmissionException(
                    "bank collection submission failed for " + collectionId, exception);
        }
    }

    public record CardVerdict(boolean authorized, String reason) {
    }

    public CardVerdict submitCardAuthorization(
            String bankId, String collectionId, long amountCents,
            String currency, String cardToken, String dueDate) {
        if (bankRegistry.byId(bankId) == null) {
            throw new IllegalStateException("unknown bank id " + bankId);
        }
        RestClient restClient = clients.get(bankId);
        try {
            CardResponse response = restClient.post()
                    .uri("/collections")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new CardSubmission(
                            collectionId, amountCents, currency, cardToken, dueDate))
                    .retrieve()
                    .body(CardResponse.class);
            if (response != null && "authorized".equals(response.status())) {
                return new CardVerdict(true, null);
            }
            if (response != null && "declined".equals(response.status())) {
                return new CardVerdict(false, response.reason());
            }
            // Unlike the retired PSP client, an auth timeout or malformed response
            // is the absence of a verdict and must ride bounded retry to the DLQ.
            throw new BankSubmissionException(
                    "card authorization returned no recognized verdict for " + collectionId,
                    new IllegalStateException(
                            response == null ? "empty response" : "unknown status " + response.status()));
        } catch (RestClientException exception) {
            throw new BankSubmissionException(
                    "card authorization submission failed for " + collectionId, exception);
        }
    }

    public CollectionStatus getCollection(String bankId, String collectionId) {
        if (bankRegistry.byId(bankId) == null) {
            throw new IllegalStateException("unknown bank id " + bankId);
        }
        RestClient restClient = clients.get(bankId);
        try {
            return restClient.get()
                    .uri("/collections/{id}", collectionId)
                    .retrieve()
                    .body(CollectionStatus.class);
        } catch (HttpClientErrorException.NotFound notFound) {
            return null;
        } catch (RestClientException exception) {
            throw new BankSubmissionException(
                    "collection status query failed for " + collectionId, exception);
        }
    }

    record CollectionSubmission(String collection_id, long amount_cents,
                                String currency, String debtor_iban,
                                String mandate_reference, String due_date) {
    }

    record CardSubmission(String collection_id, long amount_cents,
                          String currency, String card_token, String due_date) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CardResponse(String status, String reason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CollectionStatus(String collection_id,
                                   List<NotificationEntry> notifications) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record NotificationEntry(int seq, String outcome, String reason,
                                        String notification_id, String state) {
        }
    }
}
