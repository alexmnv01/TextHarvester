package com.textharvester.service;

import com.textharvester.config.AppConfig;
import com.textharvester.log.AppLogger;
import lombok.Value;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class ParseService {
    private static final String USER_AGENT = "TextHarvesterBot/0.1 (+https://github.com/alexmnv01/TextHarvester)";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final Pattern REPLY_PATTERN = Pattern.compile("^[\\p{L}0-9 .-]{2,60}\\.\\s+.+");
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    public void runSingle(AppConfig.AppSettings settings) {
        String pageUrl = settings.getSinglePageUrl();
        if (pageUrl == null || pageUrl.isBlank()) {
            AppLogger.warn("singlePageUrl is empty in config");
            return;
        }

        AppLogger.info("Loading list page: " + pageUrl);
        try {
            List<LinkItem> links = collectLinksFromListPage(pageUrl);
            AppLogger.info("Collected links: " + links.size());
            processLinks(settings.getOutputDir(), links);
        } catch (IOException e) {
            AppLogger.error("Failed to load list page: " + pageUrl, e);
        }
    }

    public void runList(AppConfig.AppSettings settings) {
        List<String> pages = settings.getListPageUrls();
        if (pages == null || pages.isEmpty()) {
            AppLogger.warn("listPageUrls is empty in config");
            return;
        }

        Map<String, LinkItem> unique = new LinkedHashMap<>();
        for (String pageUrl : pages) {
            if (pageUrl == null || pageUrl.isBlank()) {
                continue;
            }
            AppLogger.info("Loading list page: " + pageUrl);
            try {
                List<LinkItem> links = collectLinksFromListPage(pageUrl);
                for (LinkItem item : links) {
                    unique.putIfAbsent(item.getUrl(), item);
                }
                AppLogger.info("Links from page: " + links.size());
            } catch (IOException e) {
                AppLogger.error("Failed to load list page: " + pageUrl, e);
            }
        }

        AppLogger.info("Total unique links: " + unique.size());
        processLinks(settings.getOutputDir(), new ArrayList<>(unique.values()));
    }

    public void buildSiteList(AppConfig.AppSettings settings) {
        String pageUrl = settings.getSinglePageUrl();
        if (pageUrl == null || pageUrl.isBlank()) {
            AppLogger.warn("singlePageUrl is empty in config");
            return;
        }

        AppLogger.info("Building site list from: " + pageUrl);
        try {
            List<String> pageLinks = collectPaginationLinks(pageUrl);
            if (pageLinks.isEmpty()) {
                AppLogger.warn("No pagination links found");
                return;
            }

            Path outDir = Path.of(settings.getOutputDir() == null || settings.getOutputDir().isBlank()
                    ? "out-files" : settings.getOutputDir());
            Files.createDirectories(outDir);
            Path outPath = outDir.resolve("site-list.txt");
            Files.write(outPath, pageLinks, StandardCharsets.UTF_8);
            AppLogger.info("Saved site list: " + outPath + " (" + pageLinks.size() + " pages)");
        } catch (IOException e) {
            AppLogger.error("Failed to build site list", e);
        }
    }

    private List<LinkItem> collectLinksFromListPage(String pageUrl) throws IOException {
        Document doc = Jsoup.connect(pageUrl)
                .userAgent(USER_AGENT)
                .timeout((int) TIMEOUT.toMillis())
                .get();

        Elements anchors = doc.select("a[href*=/video/view.php?t=]");
        Map<String, LinkItem> unique = new LinkedHashMap<>();
        for (Element a : anchors) {
            String href = a.attr("href").trim();
            String title = a.text().trim();
            if (href.isEmpty() || title.isEmpty()) {
                continue;
            }
            String absUrl = toAbsoluteUrl(pageUrl, href);
            unique.putIfAbsent(absUrl, new LinkItem(title, absUrl));
        }

        return new ArrayList<>(unique.values());
    }

    private List<String> collectPaginationLinks(String pageUrl) throws IOException {
        Document doc = Jsoup.connect(pageUrl)
                .userAgent(USER_AGENT)
                .timeout((int) TIMEOUT.toMillis())
                .get();

        Elements candidates = doc.select("*:matchesOwn(?i)страницы");
        Map<String, String> unique = new LinkedHashMap<>();
        for (Element el : candidates) {
            Element parent = el.parent();
            if (parent == null) {
                continue;
            }
            Elements links = parent.select("a[href]");
            for (Element a : links) {
                String href = a.attr("href").trim();
                if (href.isEmpty()) {
                    continue;
                }
                String absUrl = toAbsoluteUrl(pageUrl, href);
                unique.putIfAbsent(absUrl, absUrl);
            }
        }

        if (unique.isEmpty()) {
            Elements all = doc.select("a[href*=/video/]");
            for (Element a : all) {
                String text = a.text().trim();
                if (text.matches("\\d+")) {
                    String href = a.attr("href").trim();
                    if (href.isEmpty()) {
                        continue;
                    }
                    String absUrl = toAbsoluteUrl(pageUrl, href);
                    unique.putIfAbsent(absUrl, absUrl);
                }
            }
        }

        return new ArrayList<>(unique.values());
    }

    private String extractTranscript(String pageUrl) throws IOException {
        Document doc = Jsoup.connect(pageUrl)
                .userAgent(USER_AGENT)
                .timeout((int) TIMEOUT.toMillis())
                .get();

        Element h1 = doc.selectFirst("h1");
        if (h1 == null) {
            AppLogger.warn("No h1 found on page: " + pageUrl);
            return null;
        }

        Element contentRoot = h1.parent();
        Elements siblings = contentRoot.children();
        int startIndex = siblings.indexOf(h1) + 1;
        if (startIndex <= 0 || startIndex >= siblings.size()) {
            return null;
        }

        boolean afterMeta = false;
        boolean inTranscript = false;
        List<String> lines = new ArrayList<>();

        for (int i = startIndex; i < siblings.size(); i++) {
            Element el = siblings.get(i);
            String text = normalize(el.wholeText());
            if (text.isEmpty()) {
                continue;
            }

            if (!afterMeta && isMetaLine(text)) {
                afterMeta = true;
                continue;
            }

            if (!afterMeta) {
                continue;
            }

            if (isCommentAnchor(text) || isRecommendedBlock(el, text)) {
                if (inTranscript) {
                    break;
                }
                continue;
            }

            if (isPromoLinkOnly(el, text)) {
                if (!inTranscript) {
                    continue;
                }
            }

            if (!inTranscript) {
                if (REPLY_PATTERN.matcher(text).find()) {
                    inTranscript = true;
                } else if (!isPromoLinkOnly(el, text)) {
                    inTranscript = true;
                }
            }

            if (inTranscript) {
                lines.add(text);
            }
        }

        return String.join(System.lineSeparator(), lines).trim();
    }

    private void processLinks(String outputDir, List<LinkItem> links) {
        for (LinkItem item : links) {
            AppLogger.info("Processing: " + item.getTitle() + " -> " + item.getUrl());
            try {
                String transcript = extractTranscript(item.getUrl());
                if (transcript == null || transcript.isBlank()) {
                    AppLogger.warn("Transcript not found: " + item.getUrl());
                    continue;
                }
                Path outPath = writeTranscript(outputDir, item.getTitle(), transcript);
                AppLogger.info("Saved: " + outPath);
            } catch (Exception e) {
                AppLogger.error("Failed to process: " + item.getUrl(), e);
            }
        }
    }

    private boolean isMetaLine(String text) {
        String lower = text.toLowerCase();
        return lower.contains("|") && lower.contains("просмотр") && lower.contains("текст");
    }

    private boolean isCommentAnchor(String text) {
        String lower = text.toLowerCase();
        return lower.contains("комментарии")
                || lower.contains("отправлено")
                || lower.contains("ответить")
                || lower.contains("цитировать")
                || lower.contains("страницы")
                || lower.contains("cтраницы");
    }

    private boolean isRecommendedBlock(Element el, String text) {
        if (text.toLowerCase().contains("просмотр") && el.select("img").size() > 0 && el.select("a").size() > 0) {
            return true;
        }
        return false;
    }

    private boolean isPromoLinkOnly(Element el, String text) {
        Elements links = el.select("a");
        if (links.isEmpty()) {
            return false;
        }
        String linkText = normalize(links.text());
        return !linkText.isEmpty() && linkText.equals(text);
    }

    private String normalize(String text) {
        return text == null ? "" : text.replace('\u00A0', ' ').trim();
    }

    private Path writeTranscript(String outputDir, String title, String transcript) throws IOException {
        String safeTitle = sanitizeFileName(title);
        if (safeTitle.isBlank()) {
            safeTitle = "untitled";
        }

        Path outDir = Path.of(outputDir == null || outputDir.isBlank() ? "out-files" : outputDir);
        Files.createDirectories(outDir);

        Path target = outDir.resolve(safeTitle + ".txt");
        if (Files.exists(target)) {
            String ts = LocalDateTime.now().format(FILE_TS);
            target = outDir.resolve(safeTitle + "_" + ts + ".txt");
        }

        Files.writeString(target, transcript, StandardCharsets.UTF_8);
        return target;
    }

    private String sanitizeFileName(String name) {
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|]", " ");
        cleaned = cleaned.replaceAll("\\s+", " ").trim();
        return cleaned;
    }

    private String toAbsoluteUrl(String base, String href) {
        if (href.startsWith("http://") || href.startsWith("https://")) {
            return href;
        }
        return URI.create(base).resolve(href).toString();
    }

    @Value
    private static class LinkItem {
        String title;
        String url;
    }
}
