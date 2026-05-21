package com.flower.backend.flower;

import com.oracle.bmc.auth.InstancePrincipalsAuthenticationDetailsProvider;
import com.oracle.bmc.objectstorage.ObjectStorageClient;
import com.oracle.bmc.objectstorage.requests.PutObjectRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

@Slf4j
@Service
@Profile("prod")
@RequiredArgsConstructor
public class NongsaroImageService {

    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";
    private static final int TARGET_WIDTH = 400;
    private static final float JPEG_QUALITY = 0.8f;

    private final FlowerBookRepository flowerBookRepository;

    @Value("${storage.oracle.namespace}")
    private String namespace;

    @Value("${storage.oracle.bucket}")
    private String bucket;

    @Value("${storage.oracle.region}")
    private String region;

    public CompressResult compressAndStore() {
        // Oracle Storage URL이 아닌 꽃만 DB 레벨에서 필터링
        List<FlowerBook> targets = flowerBookRepository.findNeedingImageCompression();

        log.info("농사로 이미지 압축 대상: {}개", targets.size());

        int updated = 0, skipped = 0, failed = 0;
        try (ObjectStorageClient storageClient = buildStorageClient()) {
        HttpClient httpClient = HttpClient.newHttpClient();

        for (FlowerBook flower : targets) {
            if (flower.getImageUrl() == null || flower.getImageUrl().isBlank()) {
                skipped++;
                continue;
            }

            try {
                byte[] original = downloadImage(httpClient, flower.getImageUrl());
                if (original == null) { skipped++; continue; }

                byte[] compressed = resizeAndCompress(original);

                String objectName = "flowers/" + flower.getId() + ".jpg";
                uploadToOracle(storageClient, objectName, compressed);

                String oracleUrl = String.format(
                        "https://objectstorage.%s.oraclecloud.com/n/%s/b/%s/o/%s",
                        region, namespace, bucket, objectName);

                Thread.sleep(150); // API 부하 방지 (트랜잭션 밖)
                saveFlowerImageUrl(flower.getId(), oracleUrl); // DB만 트랜잭션
                updated++;

                int originalKb = original.length / 1024;
                int compressedKb = compressed.length / 1024;
                log.info("압축 완료: {} ({}KB → {}KB, {}번)", flower.getName(), originalKb, compressedKb, updated);
            } catch (Exception e) {
                log.warn("이미지 처리 실패 - {}: {}", flower.getName(), e.getMessage());
                failed++;
            }
        }

        log.info("농사로 이미지 압축 완료 - 업데이트: {}, 건너뜀: {}, 실패: {}", updated, skipped, failed);
        return new CompressResult(updated, skipped, failed);
        } // try-with-resources: storageClient 자동 종료
    }

    @Transactional
    protected void saveFlowerImageUrl(Long flowerId, String imageUrl) {
        flowerBookRepository.findById(flowerId).ifPresent(f -> {
            f.updateImageUrl(imageUrl);
            flowerBookRepository.save(f);
        });
    }

    private byte[] downloadImage(HttpClient client, String imageUrl) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(imageUrl))
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://www.nihhs.go.kr/")
                .GET()
                .build();

        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) return null;

        String contentType = response.headers().firstValue("Content-Type").orElse("");
        if (!contentType.startsWith("image/")) return null;

        return response.body();
    }

    private byte[] resizeAndCompress(byte[] original) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Thumbnails.of(new ByteArrayInputStream(original))
                .width(TARGET_WIDTH)
                .outputQuality(JPEG_QUALITY)
                .outputFormat("jpg")
                .toOutputStream(out);
        return out.toByteArray();
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

    public record CompressResult(int updated, int skipped, int failed) {}
}
