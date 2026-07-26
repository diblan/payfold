package com.blanchaert.billing.consumer.bank;

import com.blanchaert.billing.consumer.config.BankProperties;
import com.blanchaert.billing.consumer.config.BankRegistry;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.HashMap;
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
            // A PSP failure is a payment verdict. A bank submission failure is the
            // absence of a verdict, so it must ride bounded listener retry to the DLQ.
            throw new BankSubmissionException(
                    "bank collection submission failed for " + collectionId, exception);
        }
    }

    record CollectionSubmission(String collection_id, long amount_cents,
                                String currency, String debtor_iban,
                                String mandate_reference, String due_date) {
    }
}
