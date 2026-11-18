package com.pisan.hub;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Lobi sunucusunun durumunu alip gerekirse baslatir (exaroton API). Bloklar. */
public final class LobbyApi {
    public record Target(int status, String host, int port, String address) {}

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String token, id;

    public LobbyApi(String token, String id) { this.token = token; this.id = id; }

    public boolean ready() { return token != null && !token.isBlank() && !token.startsWith("BURAYA"); }

    private JsonObject call(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.exaroton.com/v1/servers/" + id + "/" + path))
                .timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + token)
                .header("User-Agent", "PisanHub/1.0").GET().build();
        JsonObject o = JsonParser.parseString(http.send(req, HttpResponse.BodyHandlers.ofString()).body()).getAsJsonObject();
        if (!o.get("success").getAsBoolean()) throw new IllegalStateException(String.valueOf(o.get("error")));
        return o;
    }

    public Target status() throws Exception {
        JsonObject d = call("").getAsJsonObject("data");
        String host = d.get("host").isJsonNull() ? null : d.get("host").getAsString();
        String addr = d.get("address").getAsString();
        return new Target(d.get("status").getAsInt(), host == null ? addr : host, d.get("port").getAsInt(), addr);
    }

    public void start() throws Exception { call("start/"); }
}
