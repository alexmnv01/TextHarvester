package com.textharvester.service;

import com.textharvester.config.AppConfig;
import com.textharvester.log.AppLogger;
import lombok.Value;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ParseService {
    private static final String USER_AGENT = "TextHarvesterBot/0.1 (+https://github.com/alexmnv01/TextHarvester)";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

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
            for (LinkItem item : links) {
                AppLogger.info("Found: " + item.getTitle() + " -> " + item.getUrl());
            }
        } catch (IOException e) {
            AppLogger.error("Failed to load list page: " + pageUrl, e);
        }
    }

    public void runList(AppConfig.AppSettings settings) {
        AppLogger.info("List mode stub. Pages: " + settings.getListPageUrls());
    }

    public void buildSiteList(AppConfig.AppSettings settings) {
        AppLogger.info("Build-site-list mode stub.");
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
