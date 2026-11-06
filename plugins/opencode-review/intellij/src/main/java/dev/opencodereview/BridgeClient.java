package dev.opencodereview;

import com.google.gson.*;
import java.nio.file.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

final class BridgeClient {
    record Target(String registryFile, String instance, long pid, int port, String token) {}
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    static Path registry() { return Path.of(System.getProperty("opencode.review.registry", Path.of(System.getProperty("user.home"), ".local", "share", "opencode-review", "clients").toString())); }
    static Target read(String file) throws Exception {
        if (!file.matches("[0-9]+-[0-9a-f-]{36}\\.json")) throw new IllegalArgumentException("Некорректный адрес клиента.");
        Path path = registry().resolve(file);
        if (Files.isSymbolicLink(path) || Files.size(path) > 4096) throw new IllegalArgumentException("Некорректный файл клиента.");
        JsonObject j = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        int port = j.get("port").getAsInt(); String token = j.get("token").getAsString();
        String instance = j.get("instance").getAsString(); long pid = j.get("pid").getAsLong();
        if (j.get("protocol").getAsInt()!=1 || port<1 || port>65535 || !token.matches("[0-9a-f]{64}")
            || !file.equals(pid+"-"+instance+".json") || ProcessHandle.of(pid).filter(ProcessHandle::isAlive).isEmpty())
            throw new IllegalArgumentException("Клиент OpenCode уже закрыт.");
        return new Target(file, instance, pid, port, token);
    }
    static JsonObject get(Target target, String path) throws Exception { return request(target, path, null); }
    static JsonObject append(Target target, PendingTransfer pending) throws Exception {
        JsonObject body = new JsonObject(); body.addProperty("id", pending.id); body.addProperty("instance", pending.instance);
        body.addProperty("sessionID", pending.sessionID); body.addProperty("hash", pending.hash); body.addProperty("text", pending.text);
        JsonArray turns=new JsonArray();for(var note:pending.comments){JsonObject turn=new JsonObject();turn.addProperty("threadID",note.discussionID());turn.addProperty("id",note.id);turn.addProperty("text",note.comment);turns.add(turn);}body.add("turns",turns);
        body.addProperty("draftHash",pending.draftHash);
        return request(target, pending.submit?"/send":"/append", body.toString());
    }
    static JsonObject post(Target target,String path,JsonObject body)throws Exception{return request(target,path,body.toString());}
    private static JsonObject request(Target target, String path, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+target.port()+path))
            .timeout(Duration.ofSeconds(12)).header("Authorization", "Bearer "+target.token());
        if (body == null) builder.GET(); else builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        var response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.body().length()>(path.equals("/threads")?4000000:path.equals("/status")?600000:20000)) throw new java.io.IOException("Слишком большой ответ OpenCode.");
        JsonElement parsed = JsonParser.parseString(response.body());
        if (parsed.isJsonNull()) return null;
        JsonObject result = parsed.getAsJsonObject();
        if (response.statusCode()!=200) throw new Rejected(result.has("error") ? result.get("error").getAsString() : "OpenCode отклонил перенос.");
        return result;
    }
    static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    static boolean confirms(JsonObject result, PendingTransfer pending) {
        return result != null && (!pending.submit || result.has("submitted")&&result.get("submitted").getAsBoolean()) && result.has("ok") && result.get("ok").getAsBoolean()
            && pending.id.equals(string(result,"id")) && pending.hash.equals(string(result,"hash"))
            && pending.instance.equals(string(result,"instance")) && pending.sessionID.equals(string(result,"sessionID"));
    }
    static String string(JsonObject j, String key) { return j!=null && j.has(key) && !j.get(key).isJsonNull() ? j.get(key).getAsString() : ""; }
    static final class Rejected extends java.io.IOException { Rejected(String message) { super(message); } }
}
