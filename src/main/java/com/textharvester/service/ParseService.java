package com.textharvester.service;

import com.textharvester.config.AppConfig;
import com.textharvester.log.AppLogger;
import lombok.Value;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;
import org.jsoup.parser.Parser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class ParseService {
    private static final String DEFAULT_USER_AGENT = "TextHarvesterBot/0.1 (+https://github.com/alexmnv01/TextHarvester)";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);
    private static final String SITE_LIST_DIR = "out-list";
    private static final String SITE_LIST_FILE = "listPageUrls.yaml";
    private static final Pattern REPLY_PATTERN = Pattern.compile("^[\\p{L}0-9 .-]{2,60}\\.\\s+.+");
    private static final Pattern TIME_PATTERN = Pattern.compile("\\b\\d{1,2}:\\d{2}:\\d{2}\\b");
    private static final Pattern LIST_DATE_PATTERN = Pattern.compile("\\b\\d{2}\\.\\d{2}\\.\\d{2,4}\\b");
    private static final Pattern TITLE_WITH_DATE_PATTERN = Pattern.compile("^\\d{2}\\.\\d{2}\\.\\d{2,4}\\.\\s+.+");
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final Set<String> BLOCK_TAGS = Set.of("p", "div", "li", "blockquote", "pre");

    public void runSingle(AppConfig.AppSettings settings) {
        runSingle(settings, () -> false);
    }

    public void runSingle(AppConfig.AppSettings settings, BooleanSupplier isCancelled) {
        runSingle(settings, isCancelled, null, null, null);
    }

    public void runSingle(AppConfig.AppSettings settings, BooleanSupplier isCancelled,
                          AtomicInteger processed, AtomicInteger saved, AtomicReference<String> currentUrl) {
        String pageUrl = settings.getSinglePageUrl();
        if (pageUrl == null || pageUrl.isBlank()) {
            AppLogger.warn("singlePageUrl is empty in config");
            return;
        }

        AppLogger.info("Loading list page: " + pageUrl);
        try {
            List<LinkItem> links = collectLinksFromListPage(pageUrl, settings);
            AppLogger.info("Collected links: " + links.size());
            if (!isCancelled.getAsBoolean()) {
                processLinks(settings.getOutputDir(), links, isCancelled, processed, saved, currentUrl, settings.getMaxItems(), settings);
            }
        } catch (IOException e) {
            AppLogger.error("Failed to load list page: " + pageUrl, e);
        }
    }

    public void runList(AppConfig.AppSettings settings) {
        runList(settings, () -> false);
    }

    public void runList(AppConfig.AppSettings settings, BooleanSupplier isCancelled) {
        runList(settings, isCancelled, null, null, null);
    }

    public void runList(AppConfig.AppSettings settings, BooleanSupplier isCancelled,
                        AtomicInteger processed, AtomicInteger saved, AtomicReference<String> currentUrl) {
        List<String> pages = settings.getListPageUrls();
        if (pages == null || pages.isEmpty()) {
            AppLogger.warn("listPageUrls is empty in config");
            return;
        }

        Map<String, LinkItem> unique = new LinkedHashMap<>();
        for (String pageUrl : pages) {
            if (isCancelled.getAsBoolean()) {
                AppLogger.warn("List mode cancelled");
                return;
            }
            if (pageUrl == null || pageUrl.isBlank()) {
                continue;
            }
            AppLogger.info("Loading list page: " + pageUrl);
            try {
                List<LinkItem> links = collectLinksFromListPage(pageUrl, settings);
                for (LinkItem item : links) {
                    LinkItem existing = unique.get(item.getUrl());
                    if (existing == null || (!titleHasDatePrefix(existing.getTitle()) && titleHasDatePrefix(item.getTitle()))) {
                        unique.put(item.getUrl(), item);
                    }
                }
                AppLogger.info("Links from page: " + links.size());
            } catch (IOException e) {
                AppLogger.error("Failed to load list page: " + pageUrl, e);
            }
        }

        AppLogger.info("Total unique links: " + unique.size());
        processLinks(settings.getOutputDir(), new ArrayList<>(unique.values()), isCancelled, processed, saved, currentUrl, settings.getMaxItems(), settings);
    }

    public void buildSiteList(AppConfig.AppSettings settings) {
        buildSiteList(settings, () -> false);
    }

    public void buildSiteList(AppConfig.AppSettings settings, BooleanSupplier isCancelled) {
        String pageUrl = settings.getSinglePageUrl();
        if (pageUrl == null || pageUrl.isBlank()) {
            AppLogger.warn("singlePageUrl is empty in config");
            return;
        }

        AppLogger.info("Building site list from: " + pageUrl);
        try {
            if (isCancelled.getAsBoolean()) {
                AppLogger.warn("Build-site-list cancelled");
                return;
            }
            List<String> pageLinks = collectPaginationLinks(pageUrl, settings);
            if (pageLinks.isEmpty()) {
                AppLogger.warn("No pagination links found");
                return;
            }

            Path outDir = Path.of(SITE_LIST_DIR);
            Files.createDirectories(outDir);
            Path outPath = outDir.resolve(SITE_LIST_FILE);
            Files.writeString(outPath, toListPageUrlsYaml(pageLinks), StandardCharsets.UTF_8);
            AppLogger.info("Saved site list: " + outPath + " (" + pageLinks.size() + " pages)");
        } catch (IOException e) {
            AppLogger.error("Failed to build site list", e);
        }
    }

    private List<LinkItem> collectLinksFromListPage(String pageUrl, AppConfig.AppSettings settings) throws IOException {
        Document doc = connect(pageUrl, settings);

        Element scope = findListScope(doc);
        Elements anchors = scope.select("a[href*=/video/view.php?t=]");
        Map<String, LinkItem> unique = new LinkedHashMap<>();
        for (Element a : anchors) {
            String href = a.attr("href").trim();
            String title = a.text().trim();
            if (href.isEmpty() || title.isEmpty()) {
                continue;
            }
            if (!looksLikeTitle(title)) {
                continue;
            }
            String absUrl = toAbsoluteUrl(pageUrl, href);
            String date = extractDateNearLink(a);
            String titledWithDate = date == null ? title : date + ". " + title;
            unique.putIfAbsent(absUrl, new LinkItem(titledWithDate, absUrl));
        }

        return new ArrayList<>(unique.values());
    }

    private List<String> collectPaginationLinks(String pageUrl, AppConfig.AppSettings settings) throws IOException {
        Document doc = connect(pageUrl, settings);
        String normalizedBaseUrl = normalize(pageUrl);
        Map<String, String> unique = new LinkedHashMap<>();
        unique.put(normalizedBaseUrl, normalizedBaseUrl);

        Element paginationScope = findPaginationScope(doc);
        if (paginationScope == null) {
            AppLogger.warn("Pagination block not found, saved only singlePageUrl");
            return new ArrayList<>(unique.values());
        }

        Map<Integer, String> orderedPages = new TreeMap<>();
        for (Element a : paginationScope.select("a[href]")) {
            String numberText = normalize(a.text());
            if (!numberText.matches("\\d+")) {
                continue;
            }
            int pageNumber = Integer.parseInt(numberText);
            if (pageNumber <= 1) {
                continue;
            }
            String href = a.attr("href").trim();
            if (href.isEmpty()) {
                continue;
            }
            String absUrl = toAbsoluteUrl(pageUrl, href);
            orderedPages.putIfAbsent(pageNumber, absUrl);
        }

        for (String url : orderedPages.values()) {
            unique.putIfAbsent(url, url);
        }

        return new ArrayList<>(unique.values());
    }

    private Element findPaginationScope(Document doc) {
        Elements markers = doc.select("*:matchesOwn((?i)[cс]траницы\\s*:)");
        Element best = null;
        int bestScore = -1;
        for (Element marker : markers) {
            Element[] candidates = new Element[] { marker, marker.parent(), marker.parent() == null ? null : marker.parent().parent() };
            for (Element candidate : candidates) {
                if (candidate == null) {
                    continue;
                }
                int score = countNumericPageLinks(candidate);
                if (score > bestScore) {
                    bestScore = score;
                    best = candidate;
                }
            }
        }
        if (bestScore > 0) {
            return best;
        }
        return null;
    }

    private int countNumericPageLinks(Element scope) {
        int count = 0;
        for (Element a : scope.select("a[href]")) {
            if (normalize(a.text()).matches("\\d+")) {
                count++;
            }
        }
        return count;
    }

    private String toListPageUrlsYaml(List<String> pageLinks) {
        StringBuilder yaml = new StringBuilder("listPageUrls:\n");
        for (String url : pageLinks) {
            yaml.append("  - \"").append(escapeYamlDoubleQuoted(url)).append("\"\n");
        }
        return yaml.toString();
    }

    private String escapeYamlDoubleQuoted(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String extractTranscript(String pageUrl, AppConfig.AppSettings settings) throws IOException {
        AppLogger.info("Extracting transcript from: " + pageUrl);
        Document doc = connect(pageUrl, settings);

        Element h1 = doc.selectFirst("h1");
        if (h1 == null) {
            AppLogger.warn("No h1 found on page: " + pageUrl);
            return null;
        }
        AppLogger.info("Title (h1): " + normalize(h1.text()));

        Element dlBody = findDlBody(doc);
        if (dlBody != null) {
            AppLogger.info("Using <dl>/<dd>/<div class=body> transcript extraction");
            String extracted = extractFromDlBody(dlBody);
            if (extracted != null && !extracted.isBlank()) {
                AppLogger.info("Transcript lines collected: " + countLines(extracted));
                return extracted;
            }
            AppLogger.warn("DL-body extraction empty, fallback to general parser");
        } else {
            AppLogger.warn("DL-body not found, fallback to general parser");
        }

        Element contentRoot = findContentRoot(h1);
        List<LineItem> items = collectLineItemsAfterH1(contentRoot, h1);
        if (items.isEmpty()) {
            AppLogger.warn("No content items after h1");
            return null;
        }
        AppLogger.info("Content items collected: " + items.size());

        boolean afterMeta = false;
        boolean inTranscript = false;
        List<String> lines = new ArrayList<>();
        String lastLine = "";
        boolean metaFound = false;
        int inspected = 0;

        for (LineItem item : items) {
            String text = normalize(item.getText());
            if (text.isEmpty()) {
                if (inTranscript) {
                    lines.add("");
                }
                continue;
            }

            if (!afterMeta && isMetaLine(text)) {
                afterMeta = true;
                metaFound = true;
                AppLogger.info("Meta line found: " + text);
                continue;
            }

            if (!afterMeta) {
                // fallback: relaxed meta detection
                if (looksLikeMetaFallback(text)) {
                    afterMeta = true;
                    metaFound = true;
                    AppLogger.info("Meta line (fallback) found: " + text);
                }
                if (!afterMeta) {
                    continue;
                }
            }

            if (isCommentAnchor(text) || isRecommendedLine(item, text)) {
                if (inTranscript) {
                    AppLogger.info("Stop by anchor: " + text);
                    break;
                }
                continue;
            }

            if (item.isLinkOnly()) {
                if (!inTranscript) {
                    AppLogger.info("Skipping promo link: " + text);
                    continue;
                }
            }

            if (!inTranscript) {
                if (REPLY_PATTERN.matcher(text).find()) {
                    if (!metaFound) {
                        AppLogger.info("Meta not found, using reply pattern to start transcript");
                        afterMeta = true;
                        metaFound = true;
                    }
                    inTranscript = true;
                    AppLogger.info("Transcript starts (reply pattern): " + text);
                } else if (!item.isLinkOnly()) {
                    inTranscript = true;
                    AppLogger.info("Transcript starts: " + text);
                }
            }

            if (inTranscript) {
                if (!text.equals(lastLine)) {
                    lines.add(text);
                    lastLine = text;
                }
            }
            inspected++;
        }

        if (!metaFound) {
            AppLogger.warn("Meta line not found on page: " + pageUrl);
        }
        AppLogger.info("Transcript lines collected: " + lines.size());
        return String.join(System.lineSeparator(), lines).trim();
    }

    private void processLinks(String outputDir, List<LinkItem> links) {
        processLinks(outputDir, links, () -> false);
    }

    private void processLinks(String outputDir, List<LinkItem> links, BooleanSupplier isCancelled) {
        processLinks(outputDir, links, isCancelled, null, null, null, 0, null);
    }

    private void processLinks(String outputDir, List<LinkItem> links, BooleanSupplier isCancelled,
                              AtomicInteger processed, AtomicInteger saved, AtomicReference<String> currentUrl, int maxItems, AppConfig.AppSettings settings) {
        int limit = Math.max(0, maxItems);
        int processedLocal = 0;
        boolean dryRun = settings != null && settings.isDryRun();
        for (LinkItem item : links) {
            if (isCancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                AppLogger.warn("Processing cancelled");
                break;
            }
            if (limit > 0 && processedLocal >= limit) {
                AppLogger.info("Max items reached: " + limit);
                break;
            }
            if (currentUrl != null) {
                currentUrl.set(item.getUrl());
            }
            AppLogger.info("Processing: " + item.getTitle() + " -> " + item.getUrl());
            try {
                String transcript = extractTranscript(item.getUrl(), settings);
                if (transcript == null || transcript.isBlank()) {
                    AppLogger.warn("На данной странице текстовая версия ролика не обнаружена: "
                            + item.getTitle() + " -> " + item.getUrl());
                    processedLocal++;
                    continue;
                }
                if (dryRun) {
                    AppLogger.info("Dry-run: skip save for " + item.getTitle());
                } else {
                    Path outPath = writeTranscript(outputDir, item.getTitle(), transcript);
                    AppLogger.info("Saved: " + outPath);
                    if (saved != null) {
                        saved.incrementAndGet();
                    }
                }
            } catch (Exception e) {
                AppLogger.error("Failed to process: " + item.getTitle() + " -> " + item.getUrl(), e);
            } finally {
                if (processed != null) {
                    processed.incrementAndGet();
                }
                processedLocal++;
            }
        }
    }

    private boolean isMetaLine(String text) {
        String lower = text.toLowerCase();
        boolean hasPipes = lower.contains("|");
        boolean hasViews = lower.contains("просмотр") || lower.contains("views");
        boolean hasTextLink = lower.contains("текст") || lower.contains("text");
        boolean hasTime = TIME_PATTERN.matcher(lower).find();
        return hasPipes && hasViews && hasTextLink && hasTime;
    }

    private boolean looksLikeMetaFallback(String text) {
        String lower = text.toLowerCase();
        boolean hasPipes = lower.contains("|");
        boolean hasViews = lower.contains("просмотр") || lower.contains("views");
        boolean hasTextLink = lower.contains("текст") || lower.contains("text");
        return hasPipes && hasViews && hasTextLink;
    }


    private boolean isCommentAnchor(String text) {
        String lower = text.toLowerCase();
        return lower.contains("комментарии")
                || lower.contains("отправлено")
                || lower.contains("ответить")
                || lower.contains("цитировать")
                || lower.contains("страницы")
                || lower.contains("cтраницы")
                || lower.contains("в новостях");
    }

    private boolean isRecommendedLine(LineItem item, String text) {
        String lower = text.toLowerCase();
        if (lower.startsWith("image:") || lower.startsWith("изображение:")) {
            return true;
        }
        if (lower.contains("просмотр") && item.hasImage() && item.hasLink()) {
            return true;
        }
        return false;
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
            AppLogger.info("File exists, using timestamp suffix: " + ts);
        }

        Files.writeString(target, transcript, StandardCharsets.UTF_8);
        return target;
    }

    private String sanitizeFileName(String name) {
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|]", " ");
        cleaned = cleaned.replaceAll("\\s+", " ").trim();
        if (cleaned.length() > 255) {
            cleaned = cleaned.substring(0, 255).trim();
        }
        return cleaned;
    }

    private String toAbsoluteUrl(String base, String href) {
        if (href.startsWith("http://") || href.startsWith("https://")) {
            return href;
        }
        return URI.create(base).resolve(href).toString();
    }

    private Document connect(String pageUrl, AppConfig.AppSettings settings) throws IOException {
        String userAgent = DEFAULT_USER_AGENT;
        Duration timeout = DEFAULT_TIMEOUT;
        if (settings != null) {
            if (settings.getUserAgent() != null && !settings.getUserAgent().isBlank()) {
                userAgent = settings.getUserAgent();
            }
            if (settings.getTimeoutSeconds() > 0) {
                timeout = Duration.ofSeconds(settings.getTimeoutSeconds());
            }
        }
        return Jsoup.connect(pageUrl)
                .userAgent(userAgent)
                .timeout((int) timeout.toMillis())
                .get();
    }

    private Element findDlBody(Document doc) {
        Elements bodies = doc.select("dl dd div.body");
        if (bodies.isEmpty()) {
            return null;
        }
        Element best = null;
        int bestScore = -1;
        for (Element body : bodies) {
            String html = body.html();
            int bCount = body.select("b").size();
            int brCount = body.select("br").size();
            int len = normalize(Parser.unescapeEntities(html.replaceAll("(?is)<[^>]+>", ""), false)).length();
            int score = (bCount * 1000) + (brCount * 10) + len;
            if (score > bestScore) {
                bestScore = score;
                best = body;
            }
        }
        if (best != null) {
            AppLogger.info("DL-body candidates: " + bodies.size() + ", selected score: " + bestScore);
        }
        return best;
    }

    private String extractFromDlBody(Element body) {
        String extracted = extractFromDlBodyHtml(body);
        return extracted == null ? "" : extracted;
    }

    private int countLines(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return text.split("\\R", -1).length;
    }

    private String extractFromDlBodyHtml(Element body) {
        String html = body.html();
        int bIndex = html.toLowerCase().indexOf("<b>");
        if (bIndex >= 0) {
            html = html.substring(bIndex);
        }
        String withBreaks = html.replaceAll("(?i)<br\\s*/?>", "\n");
        String noTags = withBreaks.replaceAll("(?is)<[^>]+>", "");
        String unescaped = Parser.unescapeEntities(noTags, false);
        if (unescaped == null) {
            return "";
        }
        String normalized = unescaped.replaceAll("\\n{3,}", "\n\n");
        return normalized.trim();
    }

    private Element findListScope(Document doc) {
        Elements candidates = doc.select(":has(a[href*=/video/view.php?t=])");
        Element best = doc.body();
        int bestCount = 0;
        for (Element el : candidates) {
            int count = el.select("a[href*=/video/view.php?t=]").size();
            if (count > bestCount) {
                best = el;
                bestCount = count;
            }
        }
        return best == null ? doc.body() : best;
    }

    private boolean looksLikeTitle(String text) {
        if (text.length() < 5) {
            return false;
        }
        if (text.matches("\\d+")) {
            return false;
        }
        String lower = text.toLowerCase();
        return !lower.contains("страницы") && !lower.contains("pages");
    }

    private String extractDateNearLink(Element linkEl) {
        Element cursor = linkEl;
        for (int i = 0; i < 4 && cursor != null; i++) {
            String date = findFirstDate(normalize(cursor.text()));
            if (date != null) {
                return date;
            }
            cursor = cursor.parent();
        }
        return null;
    }

    private String findFirstDate(String text) {
        var matcher = LIST_DATE_PATTERN.matcher(text);
        if (matcher.find()) {
            return matcher.group();
        }
        return null;
    }

    private boolean titleHasDatePrefix(String title) {
        return TITLE_WITH_DATE_PATTERN.matcher(normalize(title)).matches();
    }

    private Element findContentRoot(Element h1) {
        Element cursor = h1;
        while (cursor != null) {
            String id = cursor.id().toLowerCase();
            String classes = String.join(" ", cursor.classNames()).toLowerCase();
            if (id.contains("content") || id.contains("main")
                    || classes.contains("content") || classes.contains("main")
                    || "article".equals(cursor.tagName())) {
                return cursor;
            }
            cursor = cursor.parent();
        }
        return h1.parent() == null ? h1 : h1.parent();
    }

    private List<LineItem> collectLineItemsAfterH1(Element root, Element h1) {
        List<LineItem> items = new ArrayList<>();
        NodeTraversor.traverse(new NodeVisitor() {
            boolean started = false;

            @Override
            public void head(Node node, int depth) {
                if (node.equals(h1)) {
                    started = true;
                    return;
                }
                if (!started) {
                    return;
                }
                if (node instanceof Element el) {
                    if ("br".equals(el.tagName())) {
                        items.add(new LineItem("", false, false, false));
                    }
                    return;
                }
                if (node instanceof TextNode textNode) {
                    String raw = textNode.text();
                    if (raw == null || raw.isBlank()) {
                        return;
                    }
                    Element parentEl = textNode.parent() instanceof Element ? (Element) textNode.parent() : null;
                    Element block = findNearestBlock(parentEl);
                    boolean linkOnly = isLinkOnlyBlock(block);
                    boolean hasImg = block != null && block.select("img").size() > 0;
                    boolean hasLink = block != null && block.select("a").size() > 0;
                    for (String line : splitLines(raw)) {
                        items.add(new LineItem(line, linkOnly, hasImg, hasLink));
                    }
                }
            }

            @Override
            public void tail(Node node, int depth) {
            }
        }, root);
        return items;
    }

    private List<String> splitLines(String raw) {
        String cleaned = raw.replace('\u00A0', ' ');
        String[] parts = cleaned.split("\\R");
        List<String> lines = new ArrayList<>();
        for (String part : parts) {
            String line = part.trim();
            if (line.isEmpty()) {
                lines.add("");
            } else {
                lines.add(line);
            }
        }
        return lines;
    }

    private Element findNearestBlock(Element el) {
        Element cursor = el;
        while (cursor != null) {
            if (BLOCK_TAGS.contains(cursor.tagName())) {
                return cursor;
            }
            cursor = cursor.parent();
        }
        return el;
    }

    private boolean isLinkOnlyBlock(Element el) {
        if (el == null) {
            return false;
        }
        Elements links = el.select("a");
        if (links.isEmpty()) {
            return false;
        }
        String linkText = normalize(links.text());
        String blockText = normalize(el.text());
        return !linkText.isEmpty() && linkText.equals(blockText);
    }

    @Value
    private static class LinkItem {
        String title;
        String url;
    }

    @Value
    private static class LineItem {
        String text;
        boolean linkOnly;
        boolean hasImage;
        boolean hasLink;

        boolean hasImage() {
            return hasImage;
        }

        boolean hasLink() {
            return hasLink;
        }
    }
}
