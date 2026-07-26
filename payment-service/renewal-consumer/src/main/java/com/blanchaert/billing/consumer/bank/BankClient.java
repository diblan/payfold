package com.blanchaert.billing.consumer.bank;

import com.blanchaert.billing.consumer.config.BankProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class BankClient {
    private final RestClient restClient;

    public BankClient(BankProperties props) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(props.timeoutMs());
        requestFactory.setReadTimeout(props.timeoutMs());
        this.restClient = RestClient.builder()
                .baseUrl(props.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    public void submitCollection(String collectionId, long amountCents,
                                 String currency, String debtorIban,
                                 String mandateReference, String dueDate) {
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
