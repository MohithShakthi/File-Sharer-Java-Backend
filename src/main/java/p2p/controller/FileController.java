package p2p.controller;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.apache.commons.io.IOUtils;
import p2p.service.FileSharer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FileController {

    private final FileSharer fileSharer;
    private final HttpServer server;
    private final String uploadDir;
    private final ExecutorService executorService;

    public FileController(int port) throws IOException {
        this.fileSharer = new FileSharer();
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.uploadDir = System.getProperty("java.io.tmpdir") + File.separator + "p2p-upload";
        this.executorService = Executors.newFixedThreadPool(10);

        File uploadDirFile = new File(uploadDir);
        if (!uploadDirFile.exists()) {
            uploadDirFile.mkdir();
        }

        server.createContext("/upload", new UploadHandler());
        server.createContext("/download", new DownloadHandler());
        server.createContext("/", new CORSHandler());
        server.setExecutor(executorService);
    }

    public void start() {
        server.start();
        System.out.println("API server started on port " + server.getAddress().getPort());
    }

    public void stop() {
        server.stop(0);
        executorService.shutdown();
        System.out.println("API server stopped");
    }

    private class CORSHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Headers headers = exchange.getResponseHeaders();
            headers.add("Access-Control-Allow-Origin", "*");
            headers.add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            headers.add("Access-Control-Allow-Headers", "Content-Type,Authorization");

            if (exchange.getRequestMethod().equals("OPTIONS")) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            String response = "NOT FOUND";
            exchange.sendResponseHeaders(404, response.getBytes().length);
            try (OutputStream oos = exchange.getResponseBody()) {
                oos.write(response.getBytes());
            }
        }
    }

    private class UploadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Headers headers = exchange.getResponseHeaders();
            headers.add("Access-Control-Allow-Origin", "*");
            headers.add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            headers.add("Access-Control-Allow-Headers", "Content-Type,Authorization");

            if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                String response = "METHOD NOT ALLOWED";
                exchange.sendResponseHeaders(405, response.getBytes().length);
                try (OutputStream oos = exchange.getResponseBody()) {
                    oos.write(response.getBytes());
                }
                return;
            }

            Headers requestHeaders = exchange.getRequestHeaders();
            String contentType = requestHeaders.getFirst("Content-Type");
            if (contentType == null || !contentType.startsWith("multipart/form-data")) {
                String response = "Bad request: Content-Type must be multipart/form-data";
                exchange.sendResponseHeaders(400, response.getBytes().length);
                try (OutputStream oos = exchange.getResponseBody()) {
                    oos.write(response.getBytes());
                }
                return;
            }

            try {
                int boundaryIndex = contentType.indexOf("boundary=");
                if (boundaryIndex == -1) {
                    String response = "Bad request: Missing boundary in Content-Type";
                    exchange.sendResponseHeaders(400, response.getBytes().length);
                    try (OutputStream oos = exchange.getResponseBody()) {
                        oos.write(response.getBytes());
                    }
                    return;
                }
                String boundary = contentType.substring(boundaryIndex + 9).trim();
                // Remove quotes if present
                if (boundary.startsWith("\"") && boundary.endsWith("\"")) {
                    boundary = boundary.substring(1, boundary.length() - 1);
                }
                
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                IOUtils.copy(exchange.getRequestBody(), baos);
                byte[] requestData = baos.toByteArray();

                Multiparser multiparser = new Multiparser(requestData, boundary);
                Multiparser.ParseResult result = multiparser.parse();

                if (result == null) {
                    String response = "Bad Result: Could not parse file content";
                    exchange.sendResponseHeaders(400, response.getBytes().length);
                    try (OutputStream oos = exchange.getResponseBody()) {
                        oos.write(response.getBytes());
                    }
                    return;
                }

                String fileName = result.fileName;
                if (fileName == null || fileName.trim().isEmpty()) {
                    fileName = "unnamed-file";
                }
                
                // Preserve original extension
                String uniqueFileName = UUID.randomUUID().toString() + "_" + sanitizeFileName(fileName);
                String filePath = uploadDir + File.separator + uniqueFileName;
                
                try (FileOutputStream fos = new FileOutputStream(filePath)) {
                    fos.write(result.fileContent);
                }
                
                int port = fileSharer.offerFile(filePath);
                new Thread(() -> fileSharer.startFileServer(port)).start();
                
                String jsonResponse = "{\"port\":" + port + ",\"fileName\":\"" + escapeJson(fileName) + "\"}";
                headers.add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, jsonResponse.getBytes().length);
                try (OutputStream oos = exchange.getResponseBody()) {
                    oos.write(jsonResponse.getBytes());
                }
            } catch (Exception e) {
                System.err.println("Error in processing file upload " + e.getMessage());
                e.printStackTrace();
                String response = "Server error " + e.getMessage();
                exchange.sendResponseHeaders(500, response.getBytes().length);
                try (OutputStream oos = exchange.getResponseBody()) {
                    oos.write(response.getBytes());
                }
            }
        }
        
        private String sanitizeFileName(String fileName) {
            return fileName.replaceAll("[^a-zA-Z0-9._-]", "_");
        }
        
        private String escapeJson(String str) {
            return str.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }

    private static class Multiparser {
        private final byte[] data;
        private final String boundary;

        public Multiparser(byte[] data, String boundary) {
            this.data = data;
            this.boundary = boundary;
        }

        public ParseResult parse() {
            try {
                // Find the header section by searching for patterns in bytes
                byte[] filenameMarker = "filename=\"".getBytes(StandardCharsets.ISO_8859_1);
                int fileNameStart = findSequence(data, filenameMarker, 0);
                if (fileNameStart == -1) return null;
                
                fileNameStart += filenameMarker.length;
                
                // Find closing quote for filename
                int fileNameEnd = -1;
                for (int i = fileNameStart; i < data.length; i++) {
                    if (data[i] == '"') {
                        fileNameEnd = i;
                        break;
                    }
                }
                if (fileNameEnd == -1) return null;
                
                String fileName = new String(data, fileNameStart, fileNameEnd - fileNameStart, StandardCharsets.UTF_8);

                // Find Content-Type (optional)
                String contentType = "application/octet-stream";
                byte[] contentTypeMarker = "Content-Type:".getBytes(StandardCharsets.ISO_8859_1);
                int contentTypeStart = findSequence(data, contentTypeMarker, fileNameEnd);
                if (contentTypeStart != -1) {
                    contentTypeStart += contentTypeMarker.length;
                    // Skip whitespace
                    while (contentTypeStart < data.length && (data[contentTypeStart] == ' ' || data[contentTypeStart] == '\t')) {
                        contentTypeStart++;
                    }
                    int contentTypeEnd = contentTypeStart;
                    while (contentTypeEnd < data.length && data[contentTypeEnd] != '\r' && data[contentTypeEnd] != '\n') {
                        contentTypeEnd++;
                    }
                    contentType = new String(data, contentTypeStart, contentTypeEnd - contentTypeStart, StandardCharsets.ISO_8859_1).trim();
                }

                // Find end of headers (double CRLF)
                byte[] headerEndMarker = "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
                int headerEnd = findSequence(data, headerEndMarker, fileNameEnd);
                if (headerEnd == -1) return null;

                int contentStart = headerEnd + headerEndMarker.length;

                // Find end boundary
                byte[] endBoundaryBytes = ("\r\n--" + boundary + "--").getBytes(StandardCharsets.ISO_8859_1);
                int contentEnd = findSequence(data, endBoundaryBytes, contentStart);
                
                if (contentEnd == -1) {
                    // Try without the trailing --
                    byte[] boundaryBytes = ("\r\n--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
                    contentEnd = findSequence(data, boundaryBytes, contentStart);
                }
                
                if (contentEnd == -1 || contentEnd < contentStart) return null;

                byte[] fileContent = new byte[contentEnd - contentStart];
                System.arraycopy(data, contentStart, fileContent, 0, fileContent.length);
                
                return new ParseResult(fileName, fileContent, contentType);

            } catch (Exception e) {
                System.err.println("Error parsing multipart data: " + e.getMessage());
                e.printStackTrace();
                return null;
            }
        }

        public static class ParseResult {
            public final String fileName;
            public final byte[] fileContent;
            public final String contentType;

            public ParseResult(String fileName, byte[] fileContent, String contentType) {
                this.fileName = fileName;
                this.fileContent = fileContent;
                this.contentType = contentType;
            }
        }

        private static int findSequence(byte[] data, byte[] sequence, int startPos) {
            outer:
            for (int i = startPos; i <= data.length - sequence.length; i++) {
                for (int j = 0; j < sequence.length; j++) {
                    if (data[i + j] != sequence[j]) {
                        continue outer;
                    }
                }
                return i;
            }
            return -1;
        }
    }

    private class DownloadHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Headers headers = exchange.getResponseHeaders();
            headers.add("Access-Control-Allow-Origin", "*");
            headers.add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            headers.add("Access-Control-Allow-Headers", "Content-Type,Authorization");

            if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                String response = "Method not allowed";
                exchange.sendResponseHeaders(405, response.getBytes().length);
                try (OutputStream oos = exchange.getResponseBody()) {
                    oos.write(response.getBytes());
                }
                return;
            }

            String path = exchange.getRequestURI().getPath();
            String[] parts = path.split("/");
            if (parts.length < 3) {
                String response = "Invalid download URL. Expected format: /download/{port}";
                exchange.sendResponseHeaders(400, response.getBytes().length);
                try (OutputStream oos = exchange.getResponseBody()) {
                    oos.write(response.getBytes());
                }
                return;
            }
            
            String portStr = parts[2];
            try {
                int port = Integer.parseInt(portStr);

                try (Socket socket = new Socket("localhost", port);
                     InputStream socketInput = socket.getInputStream()) {

                    // Read header line (filename)
                    ByteArrayOutputStream headerBaos = new ByteArrayOutputStream();
                    int b;
                    while ((b = socketInput.read()) != -1) {
                        if (b == '\n') break;
                        headerBaos.write(b);
                    }
                    
                    String header = headerBaos.toString(StandardCharsets.UTF_8.name()).trim();
                    String fileName = "downloaded-file";
                    if (header.startsWith("filename: ")) {
                        fileName = header.substring("filename: ".length()).trim();
                        // Remove any carriage return
                        fileName = fileName.replace("\r", "");
                    }

                    // Determine content type from extension
                    String contentTypeHeader = getContentType(fileName);

                    // Read remaining data (file content) into temp file
                    File tempFile = File.createTempFile("download-", getExtension(fileName));
                    try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                        byte[] buffer = new byte[8192];
                        int byteRead;
                        while ((byteRead = socketInput.read(buffer)) != -1) {
                            fos.write(buffer, 0, byteRead);
                        }
                    }

                    // Set response headers
                    headers.add("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
                    headers.add("Content-Type", contentTypeHeader);
                    headers.add("Content-Length", String.valueOf(tempFile.length()));
                    
                    exchange.sendResponseHeaders(200, tempFile.length());
                    
                    try (OutputStream oos = exchange.getResponseBody();
                         FileInputStream fis = new FileInputStream(tempFile)) {
                        byte[] buffer = new byte[8192];
                        int byteRead;
                        while ((byteRead = fis.read(buffer)) != -1) {
                            oos.write(buffer, 0, byteRead);
                        }
                        oos.flush();
                    } finally {
                        if (tempFile.exists()) {
                            tempFile.delete();
                        }
                    }
                }
            } catch (NumberFormatException e) {
                String response = "Invalid port number";
                exchange.sendResponseHeaders(400, response.getBytes().length);
                try (OutputStream oos = exchange.getResponseBody()) {
                    oos.write(response.getBytes());
                }
            } catch (Exception e) {
                System.err.println("Error downloading the file: " + e.getMessage());
                e.printStackTrace();
                String response = "Error downloading file: " + e.getMessage();
                headers.add("Content-Type", "text/plain");
                exchange.sendResponseHeaders(500, response.getBytes().length);
                try (OutputStream oos = exchange.getResponseBody()) {
                    oos.write(response.getBytes());
                }
            }
        }

        private String getExtension(String fileName) {
            int dotIndex = fileName.lastIndexOf('.');
            if (dotIndex > 0) {
                return fileName.substring(dotIndex);
            }
            return ".tmp";
        }

        private String getContentType(String fileName) {
            String lower = fileName.toLowerCase();
            if (lower.endsWith(".mp4")) return "video/mp4";
            if (lower.endsWith(".webm")) return "video/webm";
            if (lower.endsWith(".avi")) return "video/x-msvideo";
            if (lower.endsWith(".mov")) return "video/quicktime";
            if (lower.endsWith(".mkv")) return "video/x-matroska";
            if (lower.endsWith(".mp3")) return "audio/mpeg";
            if (lower.endsWith(".wav")) return "audio/wav";
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
            if (lower.endsWith(".png")) return "image/png";
            if (lower.endsWith(".gif")) return "image/gif";
            if (lower.endsWith(".pdf")) return "application/pdf";
            if (lower.endsWith(".txt")) return "text/plain";
            if (lower.endsWith(".html")) return "text/html";
            if (lower.endsWith(".json")) return "application/json";
            if (lower.endsWith(".zip")) return "application/zip";
            return "application/octet-stream";
        }
    }
}