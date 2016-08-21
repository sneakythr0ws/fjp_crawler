package org.nick.utils.crawler;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Created by Владимир on 8/20/2016.
 */
public class CrawlerTask extends RecursiveAction {

    public static final int CRAWLER_DISABLE_DEPTH_CHECK = -1;

    private static final Pattern HREF_PATTERN = Pattern.compile("<a\\s+(?:[^>]*?\\s+)?href=\"((http://|https://|/)[^\"]*)\"", Pattern.CASE_INSENSITIVE);

    private final AtomicInteger fails;
    private final URL url;
    private final int depth;
    private final int currentDepth;
    private final Set<String> visited;
    private final Consumer<URL> consumer;
    private final int failLimit;
    private final boolean currentDomainOnly;
    private final boolean checkVisit;


    public CrawlerTask(URL url, Consumer<URL> consumer) {
        this(url, consumer, CRAWLER_DISABLE_DEPTH_CHECK, 5, true, true);
    }

    public CrawlerTask(URL url, Consumer<URL> consumer, int depth, int failLimit, boolean currentDomainOnly, boolean checkVisit) {
        this(url, depth, 0, ConcurrentHashMap.newKeySet(), consumer, new AtomicInteger(0), failLimit, currentDomainOnly, checkVisit);
    }

    private CrawlerTask(URL url, int depth, int currentDepth, Set<String> visited, Consumer<URL> consumer, AtomicInteger fails, int failLimit, boolean currentDomainOnly, boolean checkVisit) {
        this.url = url;
        this.depth = depth;
        this.currentDepth = currentDepth;
        this.visited = visited;
        this.consumer = consumer;
        this.fails = fails;
        this.failLimit = failLimit;
        this.currentDomainOnly = currentDomainOnly;
        this.checkVisit = checkVisit;
    }

    @Override
    protected void compute() {
        createSubtasks().stream().map(ForkJoinTask::fork).forEach(ForkJoinTask::join);
    }

    private List<URL> getLinks(URLConnection connection) {
        String charset = null;
        if (connection.getContentType() != null && connection.getContentType().contains("charset=")) {
            charset = connection.getContentType().substring(connection.getContentType().indexOf("charset=") + 8);
        }

        //Find matches using buffer
        try (Scanner htmlScanner = new Scanner(connection.getInputStream(), charset == null ? Charset.defaultCharset().name() : charset)) {
            final List<URL> results = new ArrayList<>();
            String nextMatch;

            while ((nextMatch = htmlScanner.findWithinHorizon(HREF_PATTERN, 0)) != null) {
                try {
                    URL newURL = new URL(prepareHref(nextMatch, url));

                    final String preparedHost = prepareURL(newURL.toString());
                    if (!checkVisit || !visited.contains(preparedHost)) {
                        if (checkVisit) {
                            visited.add(preparedHost);
                        }

                        //check domain
                        if (!currentDomainOnly || newURL.getHost().equals(prepareURL(url.getHost())))
                            results.add(newURL);
                    }
                } catch (MalformedURLException e) {
                    e.printStackTrace();
                }

            }
            return results;
        } catch (IOException e) {
            e.printStackTrace();
        }

        return Collections.emptyList();
    }

    private String prepareHref(String str, URL url) {
        String step1 = prepareURL(str);
        String step2 = step1.substring(step1.toLowerCase().indexOf("href=") + 5);

        //for canonical links
        if (step2.startsWith("/")) {
            //for yandex links like //home.yandex.ru/*
            if (step2.startsWith("//")) {
                return url.getProtocol() + ":" + step2;
            } else {
                return url.getProtocol() + "://" + url.getHost() + (url.getPort() == -1 ? "" : ":" + url.getPort()) + step2;
            }
        } else {
            return step2;
        }
    }

    static String prepareURL(String url) {
        String step1 = url.replaceAll("(www.)|([ ]|[\"])", "");
        return step1.endsWith("/") ? step1.substring(0, step1.length() - 1) : step1;
    }

    private List<CrawlerTask> createSubtasks() {
        if (fails.get() < failLimit) {
            int newDepth = this.currentDepth + 1;
            if (depth == CRAWLER_DISABLE_DEPTH_CHECK || newDepth < depth) {
                try {
                    URLConnection connection = url.openConnection();
                    connection.connect();

                    int responseCode = ((HttpURLConnection) connection).getResponseCode();

                    if (responseCode >= HttpURLConnection.HTTP_INTERNAL_ERROR && responseCode < 600) {
                        if (fails.incrementAndGet() >= failLimit) {
                            System.out.println("Exceeded limit of errors");
                            return Collections.emptyList();
                        }
                    } else {
                        List<URL> results = getLinks(connection);

                        results.forEach(consumer);

                        return results.stream().map(e -> new CrawlerTask(e, depth, newDepth, visited, consumer, fails, failLimit, currentDomainOnly, checkVisit)).collect(Collectors.toList());
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        return Collections.emptyList();
    }
}