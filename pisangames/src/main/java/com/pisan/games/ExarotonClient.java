package com.pisan.games;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Minimal exaroton API istemcisi. Tum metodlar bloklar; ana thread'den cagirma. */
public final class ExarotonClient {
    public static final int OFFLINE = 0, ONLINE = 1, STARTING = 2, STOPPING = 3, RESTARTING = 4,
            SAVING = 5, LOADING = 6, CRASHED = 7, PENDING = 8, PREPARING = 10;

    public record ServerInfo(String id, String name, String address, String host, int port, int status,
                             int players, int maxPlayers) {
        /** Transfer icin baglanilacak host: acikken dugum hostu, yoksa adres. */
        public String connectHost() { return host != null && !host.isEmpty() ? host : address; }
    }

    private static final String BASE = "https://api.exaroton.com/v1/";
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String token;

    public ExarotonClient(String token) { this.token = token; }

    public boolean hasToken() { return token != null && !token.isBlank() && !token.startsWith("BURAYA"); }

    private JsonObject call(String method, String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + path))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + token)
                .header("User-Agent", "PisanGames/1.0")
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        JsonObject o = JsonParser.parseString(res.body()).getAsJsonObject();
        if (!o.get("success").getAsBoolean()) {
            JsonElement e = o.get("error");
            throw new IllegalStateException(e == null || e.isJsonNull() ? "HTTP " + res.statusCode() : e.getAsString());
        }
        return o;
    }

    public ServerInfo get(String id) throws Exception {
        JsonObject d = call("GET", "servers/" + id + "/").getAsJsonObject("data");
        JsonObject p = d.getAsJsonObject("players");
        return new ServerInfo(
                d.get("id").getAsString(), d.get("name").getAsString(), d.get("address").getAsString(),
                str(d.get("host")), d.get("port").isJsonNull() ? 25565 : d.get("port").getAsInt(),
                d.get("status").getAsInt(),
                p == null ? 0 : p.get("count").getAsInt(), p == null ? 0 : p.get("max").getAsInt());
    }

    public void start(String id) throws Exception { call("GET", "servers/" + id + "/start/"); }

    private static String str(JsonElement e) { return e == null || e.isJsonNull() ? null : e.getAsString(); }
}
