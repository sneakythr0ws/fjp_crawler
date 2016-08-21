package org.nick.utils.crawler;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by Vladimir on 8/20/2016.
 */
public class CrawlerTest {
    private static Server server;
    private static URI serverUri;

    @BeforeClass
    public static void startJetty() throws Exception {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0); // auto-bind to available port
        server.addConnector(connector);

        ServletContextHandler withoutErrors = createResourceServlet("/withouterrors", System.getProperty("user.dir") + "/src/test/resources/withouterrors");
        ServletContextHandler withErrors = createResourceServlet("/witherrors", System.getProperty("user.dir") + "/src/test/resources/witherrors");
        ServletContextHandler error = getErrorServlet("/error");

        ContextHandlerCollection contexts = new ContextHandlerCollection();
        contexts.setHandlers(new Handler[]{withoutErrors, withErrors, error});

        server.setHandler(contexts);

        server.start();

        // Determine Base URI for Server
        String host = connector.getHost();
        if (host == null) {
            host = "localhost";
        }
        int port = connector.getLocalPort();
        serverUri = new URI(String.format("http://%s:%d/", host, port));

        //System.out.println(serverUri);
        //server.join();
    }

    private static ServletContextHandler createResourceServlet(String path, String dir) {
        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath(path);
        ServletHolder defaultServ = new ServletHolder("default", DefaultServlet.class);
        defaultServ.setInitParameter("resourceBase", dir);
        defaultServ.setInitParameter("dirAllowed", "true");
        context.addServlet(defaultServ, "/");
        return context;
    }

    private static ServletContextHandler getErrorServlet(String path) {
        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath(path);
        ServletHolder servletHolder = new ServletHolder(new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
                throw new ServletException("Generated 500" + path);
            }
        });
        context.addServlet(servletHolder, "/");
        return context;
    }


    @AfterClass
    public static void stopJetty() {
        try {
            server.stop();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    //@Test
    public void googleTest() throws Exception {
        new ForkJoinPool().invoke(new CrawlerTask(new URL("https://play.google.com"), System.out::println, 2, 5, true));
    }

    @Test
    public void failLimitTest() throws MalformedURLException {
        //System.out.println(IOUtils.toString(serverUri.resolve("/witherrors").toURL().openStream()));
        new ForkJoinPool().invoke(new CrawlerTask(serverUri.resolve("/witherrors").toURL(), System.out::println,
                CrawlerTask.CRAWLER_DISABLE_DEPTH_CHECK, 100000000, true));
    }

    @Test
    public void quantityTest() throws MalformedURLException {
        AtomicInteger count = new AtomicInteger();
        //System.out.println(IOUtils.toString(serverUri.resolve("/withouterrors").toURL().openStream()));
        new ForkJoinPool().invoke(new CrawlerTask(serverUri.resolve("/withouterrors").toURL(), e -> count.incrementAndGet()));
        int found = count.get();
        Assert.assertEquals("Found only " + found + " of  4", found, 4);
    }

    @Test
    public void visitConditionTest() throws MalformedURLException {
        CopyOnWriteArrayList<URL> results = new CopyOnWriteArrayList<>();
        new ForkJoinPool().invoke(new CrawlerTask(serverUri.resolve("/withouterrors").toURL(), results::add));

        Assert.assertEquals("", results.size(), results.stream().map(e -> CrawlerTask.prepareURL(e.toString())).distinct().count());
    }
}