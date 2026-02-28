package com.textharvester.service;

import com.textharvester.config.AppConfig;
import com.textharvester.log.AppLogger;

public class ParseService {
    public void runSingle(AppConfig.AppSettings settings) {
        AppLogger.info("Single mode stub. Will parse: " + settings.getSinglePageUrl());
    }

    public void runList(AppConfig.AppSettings settings) {
        AppLogger.info("List mode stub. Pages: " + settings.getListPageUrls());
    }

    public void buildSiteList(AppConfig.AppSettings settings) {
        AppLogger.info("Build-site-list mode stub.");
    }
}
