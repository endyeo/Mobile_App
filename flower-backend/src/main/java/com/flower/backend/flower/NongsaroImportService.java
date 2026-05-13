package com.flower.backend.flower;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class NongsaroImportService {

    private static final String BASE_URL = "http://apis.data.go.kr/1390804/NihhsTodayFlowerInfo01";

    private final FlowerBookRepository flowerRepository;
    private final FlowerCategoryRepository categoryRepository;

    @Value("${nongsaro.api-key:}")
    private String apiKey;

    // @Transactional 제거 — 외부 API 호출 중 커넥션 점유 방지
    public ImportResult importAll() {
        if (apiKey.isBlank()) {
            throw new RuntimeException("NONGSARO_API_KEY가 설정되지 않았습니다.");
        }

        RestTemplate restTemplate = new RestTemplate();
        int saved = 0, skipped = 0;

        for (int month = 1; month <= 12; month++) {
            List<FlowerListItem> items = fetchList(restTemplate, month);
            log.info("{}월 꽃 목록: {}개", month, items.size());

            for (FlowerListItem item : items) {
                if (flowerRepository.findByDataNo(item.dataNo).isPresent()) {
                    skipped++;
                    continue;
                }

                try {
                    FlowerDetailData detail = fetchDetail(restTemplate, item.dataNo); // 외부 API
                    Thread.sleep(100); // API 부하 방지 (트랜잭션 밖)
                    saveFlower(item, detail); // DB 저장만 트랜잭션
                    saved++;
                } catch (Exception e) {
                    log.warn("꽃 저장 실패 - dataNo={}, name={}: {}", item.dataNo, item.flowNm, e.getMessage());
                }
            }
        }

        log.info("농사로 데이터 수집 완료 - 저장: {}, 건너뜀: {}", saved, skipped);
        return new ImportResult(saved, skipped);
    }

    private List<FlowerListItem> fetchList(RestTemplate restTemplate, int month) {
        try {
            String url = UriComponentsBuilder.fromHttpUrl(BASE_URL + "/selectTodayFlowerList01")
                    .queryParam("serviceKey", apiKey)
                    .queryParam("fMonth", month)
                    .queryParam("numOfRows", "31")
                    .build(true).toUriString();

            String xml = restTemplate.getForObject(url, String.class);
            return parseList(xml);
        } catch (Exception e) {
            log.warn("{}월 목록 조회 실패: {}", month, e.getMessage());
            return List.of();
        }
    }

    @Transactional
    protected void saveFlower(FlowerListItem item, FlowerDetailData detail) {
        FlowerCategory category = matchCategory(item.flowNm);
        flowerRepository.save(FlowerBook.builder()
                .dataNo(item.dataNo).name(item.flowNm).scientificName(detail.sciNm)
                .bloomMonth(item.fMonth).bloomDay(item.fDay).flowerLanguage(detail.flowLang)
                .description(detail.fContent).growTips(detail.fGrow).imageUrl(detail.imgUrl)
                .category(category).source("NONGSARO").status("COMPLETE").build());
    }

    private FlowerDetailData fetchDetail(RestTemplate restTemplate, String dataNo) {
        try {
            String url = UriComponentsBuilder.fromHttpUrl(BASE_URL + "/selectTodayFlowerView01")
                    .queryParam("serviceKey", apiKey)
                    .queryParam("dataNo", dataNo)
                    .build(true).toUriString();

            String xml = restTemplate.getForObject(url, String.class);
            return parseDetail(xml);
        } catch (Exception e) {
            log.warn("상세 조회 실패 - dataNo={}: {}", dataNo, e.getMessage());
            return new FlowerDetailData("", "", "", "", "");
        }
    }

    private FlowerCategory matchCategory(String flowerName) {
        // 꽃 이름으로 카테고리 매칭 시도
        return categoryRepository.findAll().stream()
                .filter(c -> !c.getName().equals("기타") && flowerName.contains(c.getName()))
                .findFirst()
                .orElseGet(() -> categoryRepository.findByName("기타").orElse(null));
    }

    private List<FlowerListItem> parseList(String xml) {
        List<FlowerListItem> items = new ArrayList<>();
        if (xml == null) return items;

        Pattern p = Pattern.compile("<result>(.*?)</result>", Pattern.DOTALL);
        Matcher m = p.matcher(xml);
        while (m.find()) {
            String block = m.group(1);
            items.add(new FlowerListItem(
                    extract(block, "dataNo"),
                    extract(block, "flowNm"),
                    parseInt(extract(block, "fMonth")),
                    parseInt(extract(block, "fDay"))
            ));
        }
        return items;
    }

    private FlowerDetailData parseDetail(String xml) {
        if (xml == null) return new FlowerDetailData("", "", "", "", "");
        return new FlowerDetailData(
                firstNonEmpty(extract(xml, "fNm"), extract(xml, "flowNm")),
                extract(xml, "sciNm"),
                firstNonEmpty(extract(xml, "fLang"), extract(xml, "flowLang")),
                stripHtml(extract(xml, "fContent")),
                stripHtml(extract(xml, "fGrow")),
                firstNonEmpty(extract(xml, "imgUrl"), extract(xml, "imgUrl1"))
        );
    }

    private String extract(String xml, String tag) {
        Pattern p = Pattern.compile("<" + tag + ">(?:<!\\[CDATA\\[)?(.*?)(?:\\]\\]>)?</" + tag + ">", Pattern.DOTALL);
        Matcher m = p.matcher(xml);
        return m.find() ? m.group(1).trim() : "";
    }

    private String stripHtml(String html) {
        if (html == null) return "";
        return html.replaceAll("<[^>]*>", "").replaceAll("&nbsp;", " ")
                .replaceAll("&amp;", "&").replaceAll("\\s+", " ").trim();
    }

    private String firstNonEmpty(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return "";
    }

    private int parseInt(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return 0; }
    }

    record FlowerListItem(String dataNo, String flowNm, int fMonth, int fDay) {}
    record FlowerDetailData(String flowNm, String sciNm, String flowLang,
                            String fContent, String fGrow, String imgUrl) {
        FlowerDetailData(String flowNm, String sciNm, String flowLang, String fContent, String fGrow) {
            this(flowNm, sciNm, flowLang, fContent, fGrow, "");
        }
    }
    public record ImportResult(int saved, int skipped) {}
}
