package com.flower.backend.storage;

import com.oracle.bmc.auth.InstancePrincipalsAuthenticationDetailsProvider;
import com.oracle.bmc.objectstorage.ObjectStorageClient;
import com.oracle.bmc.objectstorage.requests.PutObjectRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

/**
 * Oracle Cloud Object Storage 업로드 서비스 (운영 환경 전용)
 * Instance Principal 인증 방식 — VM 자체가 신원 증명, 키 파일 불필요
 *
 * 사전 설정 필요 (Oracle Cloud 콘솔):
 *   1. Dynamic Group 생성: VM 인스턴스를 그룹으로 묶기
 *   2. Policy 추가: Allow dynamic-group {그룹명} to manage objects in compartment {컴파트먼트명}
 */
@Slf4j
@Service
@Profile("prod")
public class OracleStorageService implements StorageService {

    private final ObjectStorageClient storageClient;

    @Value("${storage.oracle.namespace}")
    private String namespace;

    @Value("${storage.oracle.bucket}")
    private String bucket;

    @Value("${storage.oracle.region}")
    private String region;

    public OracleStorageService() {
        // Instance Principal: VM 자체 인증, 별도 키 설정 불필요
        InstancePrincipalsAuthenticationDetailsProvider provider =
                InstancePrincipalsAuthenticationDetailsProvider.builder().build();
        this.storageClient = ObjectStorageClient.builder().build(provider);
    }

    @Override
    public String upload(MultipartFile file) {
        try {
            String ext = getExtension(file.getOriginalFilename());
            String objectName = "community/" + UUID.randomUUID() + ext;

            PutObjectRequest request = PutObjectRequest.builder()
                    .namespaceName(namespace)
                    .bucketName(bucket)
                    .objectName(objectName)
                    .contentType(file.getContentType())
                    .contentLength(file.getSize())
                    .putObjectBody(file.getInputStream())
                    .build();

            storageClient.putObject(request);

            // Public URL (버킷이 공개 설정된 경우)
            return String.format(
                    "https://objectstorage.%s.oraclecloud.com/n/%s/b/%s/o/%s",
                    region, namespace, bucket, objectName
            );
        } catch (IOException e) {
            log.error("Oracle Cloud Storage 업로드 실패", e);
            throw new RuntimeException("이미지 업로드에 실패했습니다.");
        }
    }

    private String getExtension(String filename) {
        if (filename == null || !filename.contains(".")) return ".jpg";
        return filename.substring(filename.lastIndexOf("."));
    }
}
