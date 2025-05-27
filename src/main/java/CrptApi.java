import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class CrptApi {
    private static final String URL = "https://ismp.crpt.ru" + "/api/v3/lk/documents/create";
    private static final String CONTENT_TYPE = "application/json";
    private static final String TOKEN = "token"; //заменить на реальный токен

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Lock lock = new ReentrantLock();
    private final TimeUnit timeUnit;
    private final int requestLimit;
    private final Semaphore semaphore;
    private long lastRefillTime;

    public CrptApi(TimeUnit timeUnit, int requestLimit) {
        if (requestLimit <= 0) {
            throw new IllegalArgumentException("Максимальное количество запросов в единицу времени должно быть больше нуля");
        }
        this.timeUnit = timeUnit;
        this.requestLimit = requestLimit;
        this.semaphore = new Semaphore(requestLimit);
    }

    public String createDocument(Document document, String signature) throws IOException, InterruptedException {
        tryAcquirePermit();

        String jsonDocument = mapper.writeValueAsString(document);
        RequestBody requestBody = RequestBody.builder()
                .documentFormat(DocumentFormat.MANUAL)
                .productDocument(encodeToBase64(jsonDocument))
                .signature(signature)
                .type(Type.LP_INTRODUCE_GOODS)
                .build();

        String jsonRequestBody = mapper.writeValueAsString(requestBody);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(URL))
                .header("Content-Type", CONTENT_TYPE)
                .header("Authorization", "Bearer " + TOKEN)
                .POST(HttpRequest.BodyPublishers.ofString(jsonRequestBody))
                .build();

        return httpClient.send(request, HttpResponse.BodyHandlers.ofString()).body();
    }


    private String encodeToBase64(String string) {
        return Base64.getEncoder().encodeToString(string.getBytes());
    }

    private void tryAcquirePermit() throws InterruptedException {
        lock.lock();

        try {
            refillPermitsIfNeeded();
            if (semaphore.tryAcquire()) {
                return;
            }
        } finally {
            lock.unlock();
        }

        semaphore.acquire();
    }

    private void refillPermitsIfNeeded() {
        long currentTime = System.currentTimeMillis();
        long timePassed = currentTime - lastRefillTime;
        long timeUnitMillis = timeUnit.toMillis(1);

        if (timePassed >= timeUnitMillis) {
            int permitsToAdd = requestLimit - semaphore.availablePermits();
            if (permitsToAdd > 0) {
                semaphore.release(permitsToAdd);
            }
            lastRefillTime = currentTime;
        }
    }

    @Builder
    @Getter
    @Setter
    public static class RequestBody {
        @JsonProperty("document_format")
        private DocumentFormat documentFormat;
        @JsonProperty("product_document")
        private String productDocument;
        @JsonProperty("product_group")
        private String productGroup;
        private String signature;
        private Type type;
    }

    public enum DocumentFormat {
        MANUAL,
        XML,
        CSV
    }

    public enum Type {
        LP_INTRODUCE_GOODS,
        LP_INTRODUCE_GOODS_CSV,
        LP_INTRODUCE_GOODS_XML
    }

    @Getter
    @Setter
    public static class Document {
        private Description description;
        @JsonProperty("doc_id")
        private String docId;
        @JsonProperty("doc_status")
        private String docStatus;
        @JsonProperty("doc_type")
        private String docType;
        private boolean importRequest;
        @JsonProperty("owner_inn")
        private String ownerInn;
        @JsonProperty("participant_inn")
        private String participantInn;
        @JsonProperty("producer_inn")
        private String producerInn;
        @JsonProperty("production_date")
        private String productionDate;
        @JsonProperty("production_type")
        private ProductionType productionType;
        private List<Product> products;
        @JsonProperty("reg_date")
        private String regDate;
        @JsonProperty("reg_number")
        private String regNumber;
    }

    @Getter
    @Setter
    public static class Description {
        private String participantInn;
    }

    public enum ProductionType {
        OWN_PRODUCTION,
        CONTRACT_PRODUCTION
    }

    @Getter
    @Setter
    public static class Product {
        @JsonProperty("certificate_document")
        private CertificateDocument certificateDocument;
        @JsonProperty("certificate_document_date")
        private String certificateDocumentDate;
        @JsonProperty("certificate_document_number")
        private String certificateDocumentNumber;
        @JsonProperty("owner_inn")
        private String ownerInn;
        @JsonProperty("producer_inn")
        private String producerInn;
        @JsonProperty("production_date")
        private String productionDate;
        @JsonProperty("tnved_code")
        private String tnvedCode;
        @JsonProperty("uit_code")
        private String uitCode;
        @JsonProperty("uitu_code")
        private String uituCode;
    }

    public enum CertificateDocument {
        CONFORMITY_CERTIFICATE,
        CONFORMITY_DECLARATION
    }
}
