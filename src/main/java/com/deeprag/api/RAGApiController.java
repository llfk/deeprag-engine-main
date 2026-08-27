package com.deeprag.api;

import com.deeprag.generator.GenerationResult;
import com.deeprag.log.ConsoleLog;
import com.deeprag.strategy.RAGStrategy;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.util.*;

public class RAGApiController {

    private final HttpServer server;
    private final RAGStrategy strategy;
    private final RAGIndexer indexer;
    private final ObjectMapper mapper = new ObjectMapper();

    public RAGApiController(int port, RAGStrategy strategy, RAGIndexer indexer) throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.strategy = strategy;
        this.indexer = indexer;
        setupRoutes();
    }

    /** 兼容旧调用方（Stage 1/2/3 仍传入 cache/metrics 但不再使用） */
    public RAGApiController(int port, RAGStrategy strategy, RAGIndexer indexer,
                             Object cache, Object metrics) throws IOException {
        this(port, strategy, indexer);
    }

    private void setupRoutes() {
        HttpHandler query = this::handleQuery;
        HttpHandler docs = this::handleDocuments;
        HttpHandler colls = this::handleCollections;
        server.createContext("/api/v1/query", exchange -> corsAndHandle(exchange, query));
        server.createContext("/api/v1/documents", exchange -> corsAndHandle(exchange, docs));
        server.createContext("/api/v1/collections", exchange -> corsAndHandle(exchange, colls));
        server.createContext("/", this::handleFrontend);
        server.setExecutor(null);
    }

    private void corsAndHandle(HttpExchange exchange, HttpHandler handler) throws IOException {
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            var h = exchange.getResponseHeaders();
            h.set("Access-Control-Allow-Origin", "*");
            h.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            h.set("Access-Control-Allow-Headers", "Content-Type");
            exchange.sendResponseHeaders(204, -1);
            return;
        }
        handler.handle(exchange);
    }

    public void start() {
        server.start();
        ConsoleLog.info("服务启动在端口 " + server.getAddress().getPort());
    }

    public void stop() {
        server.stop(0);
    }

    private void handleQuery(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendError(exchange, 405, "METHOD_NOT_ALLOWED", "只支持 POST");
            return;
        }

        try {
            String body = readRequestBody(exchange);
            Map<String, Object> request = mapper.readValue(body, Map.class);

            String query = (String) request.get("query");
            String collection = (String) request.getOrDefault("collection", "knowledge");

            if (query == null || query.isBlank()) {
                sendError(exchange, 400, "BAD_REQUEST", "query 不能为空");
                return;
            }

            long start = System.currentTimeMillis();
            GenerationResult result = strategy.execute(query, collection);
            long duration = System.currentTimeMillis() - start;

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("answer", result.getAnswer());
            response.put("citations", result.getCitedChunks());
            response.put("hallucinationScore", result.getHallucinationScore());
            response.put("retrievedChunks", result.getRetrievedChunks().size());
            response.put("latencyMs", duration);

            sendResponse(exchange, 200, response);

        } catch (Exception e) {
            ConsoleLog.error("查询处理失败: " + e.getMessage());
            sendError(exchange, 500, "INTERNAL_ERROR", e.getMessage());
        }
    }

    private void handleDocuments(HttpExchange exchange) throws IOException {
        if ("POST".equals(exchange.getRequestMethod())) {
            try {
                String body = readRequestBody(exchange);
                Map<String, Object> request = mapper.readValue(body, Map.class);
                String filePath = (String) request.get("filePath");

                if (filePath == null) {
                    sendError(exchange, 400, "BAD_REQUEST", "filePath 不能为空");
                    return;
                }

                String collection = indexer.index(filePath);
                Map<String, Object> response = Map.of(
                        "status", "indexed",
                        "collection", collection
                );
                sendResponse(exchange, 200, response);
            } catch (Exception e) {
                sendError(exchange, 500, "INDEX_ERROR", e.getMessage());
            }
        } else {
            sendError(exchange, 405, "METHOD_NOT_ALLOWED", "只支持 POST");
        }
    }

    private void handleCollections(HttpExchange exchange) throws IOException {
        try {
            List<String> collections = indexer.listCollections();
            sendResponse(exchange, 200, Map.of("collections", collections));
        } catch (Exception e) {
            sendError(exchange, 500, "ERROR", e.getMessage());
        }
    }

    private String readRequestBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody();
             BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }

    private void sendResponse(HttpExchange exchange, int statusCode, Object body) throws IOException {
        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(body);
        byte[] bytes = json.getBytes();
        var headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        headers.set("Access-Control-Allow-Origin", "*");
        headers.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        headers.set("Access-Control-Allow-Headers", "Content-Type");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendError(HttpExchange exchange, int statusCode, String error, String message) throws IOException {
        Map<String, Object> body = Map.of("error", error, "message", message, "status", statusCode);
        sendResponse(exchange, statusCode, body);
    }

    private void handleFrontend(HttpExchange exchange) throws IOException {
        var file = new File("frontend/index.html");
        if (!file.exists()) {
            file = new File("../frontend/index.html");
        }
        if (!file.exists()) {
            String html = "<html><body><h2>DeepRAG Console</h2><p>前端文件未找到，请直接打开 frontend/index.html</p></body></html>";
            byte[] bytes = html.getBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
            return;
        }
        byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
    }
}
