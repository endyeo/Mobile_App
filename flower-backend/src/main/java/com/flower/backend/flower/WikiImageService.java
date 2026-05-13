package com.flower.backend.flower;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oracle.bmc.auth.InstancePrincipalsAuthenticationDetailsProvider;
import com.oracle.bmc.objectstorage.ObjectStorageClient;
import com.oracle.bmc.objectstorage.requests.PutObjectRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

@Slf4j
@Service
@Profile("prod")
@RequiredArgsConstructor
public class WikiImageService {

    private static final String WIKI_API = "https://ko.wikipedia.org/api/rest_v1/page/summary/";
    private static final String USER_AGENT = "OurT-FlowerApp/1.0 (https://ourt.kro.kr)";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final FlowerBookRepository flowerBookRepository;

    @Value("${storage.oracle.namespace}")
    private String namespace;

    @Value("${storage.oracle.bucket}")
    private String bucket;

    @Value("${storage.oracle.region}")
    private String region;

    public FetchResult fetchAndStoreImages() {
        List<FlowerBook> flowers = flowerBookRepository.findAll();
        int updated = 0, skipped = 0, failed = 0;

        try (ObjectStorageClient storageClient = buildStorageClient()) {
        HttpClient httpClient = HttpClient.newHttpClient();

        for (FlowerBook flower : flowers) {
            try {
                String thumbnailUrl = fetchWikiThumbnail(httpClient, flower.getName());
                if (thumbnailUrl == null) { skipped++; continue; }

                byte[] imageBytes = downloadImage(httpClient, thumbnailUrl);
                if (imageBytes == null) { skipped++; continue; }

                String objectName = "flowers/" + flower.getId() + ".jpg";
                uploadToOracle(storageClient, objectName, imageBytes);
                String oracleUrl = String.format(
                        "https://objectstorage.%s.oraclecloud.com/n/%s/b/%s/o/%s",
                        region, namespace, bucket, objectName);

                Thread.sleep(200); // API 부하 방지 (트랜잭션 밖)
                updateFlowerImageUrl(flower.getId(), oracleUrl); // DB만 트랜잭션
                updated++;
                log.info("이미지 저장 완료: {} ({})", flower.getName(), updated);
            } catch (Exception e) {
                log.warn("이미지 처리 실패 - {}: {}", flower.getName(), e.getMessage());
                failed++;
            }
        }

        log.info("위키 이미지 수집 완료 - 업데이트: {}, 건너뜀: {}, 실패: {}", updated, skipped, failed);
        return new FetchResult(updated, skipped, failed);
        } // try-with-resources: storageClient 자동 종료
    }

    private String fetchWikiThumbnail(HttpClient client, String flowerName) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(WIKI_API + java.net.URLEncoder.encode(flowerName, "UTF-8")))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) return null;

        JsonNode root = MAPPER.readTree(response.body());
        JsonNode thumbnail = root.path("thumbnail");
        if (thumbnail.isMissingNode()) return null;

        return thumbnail.path("source").asText(null);
    }

    @Transactional
    protected void updateFlowerImageUrl(Long flowerId, String imageUrl) {
        flowerBookRepository.findById(flowerId).ifPresent(f -> {
            f.updateImageUrl(imageUrl);
            flowerBookRepository.save(f);
        });
    }

    private byte[] downloadImage(HttpClient client, String imageUrl) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(imageUrl))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();

        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) return null;
        return response.body();
    }

    private void uploadToOracle(ObjectStorageClient client, String objectName, byte[] data) {
        PutObjectRequest request = PutObjectRequest.builder()
                .namespaceName(namespace)
                .bucketName(bucket)
                .objectName(objectName)
                .contentType("image/jpeg")
                .contentLength((long) data.length)
                .putObjectBody(new ByteArrayInputStream(data))
                .build();
        client.putObject(request);
    }

    private ObjectStorageClient buildStorageClient() {
        InstancePrincipalsAuthenticationDetailsProvider provider =
                InstancePrincipalsAuthenticationDetailsProvider.builder().build();
        return ObjectStorageClient.builder().build(provider);
    }

    public record FetchResult(int updated, int skipped, int failed) {}
}
