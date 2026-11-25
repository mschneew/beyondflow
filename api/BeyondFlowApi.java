import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * BeyondFlow API: verwaltet Flows (Camel-YAML) als Dateien, versioniert sie mit git
 * und liefert Status und Logs der Laufzeit. Nur lokal erreichbar (127.0.0.1).
 *
 *   GET    /api/flows            Liste der Flows (aktiv und deaktiviert)
 *   GET    /api/flows/{id}       YAML eines Flows
 *   PUT    /api/flows/{id}       Flow speichern und aktivieren (Laufzeit laedt ihn automatisch)
 *   POST   /api/flows/{id}/stop  Flow deaktivieren
 *   GET    /api/status           laufende Routen (camel get route)
 *   GET    /api/logs             letzte Zeilen der Laufzeit (~/beyondflow/logs/runtime.log)
 *   POST   /api/runtime/restart  Laufzeit neu starten (systemctl --user restart beyondflow-runtime)
 *   POST   /api/flows/{id}/start Deaktivierten Flow wieder aktivieren
 *   GET    /api/resources        CPU und Speicher je BeyondFlow-Prozess und der VM
 *   GET    /api/version          eingespielte Version (Datei ~/beyondflow/VERSION, vom Update-Skript gesetzt)
 *   GET    /api/insights         Betriebsassistent: Diagnose je System aus dem Log der letzten 30 Minuten
 *   POST   /api/insights/ai      KI-Zusammenfassung (optional, BF_AI_KEY in secrets.env)
 *   POST   /api/check/{system}   Verbindung pruefen (salesforce | sap | servicenow)
 *   POST   /api/pause/{system}   Alle Flows eines Systems pausieren; /api/resume/{system} setzt sie fort
 */
public class BeyondFlowApi {
    static final Path HOME = Paths.get(System.getProperty("user.home"));
    static final Path FLOWS = Paths.get(System.getenv().getOrDefault("BF_FLOWS", HOME.resolve("beyondflow/flows").toString()));
    static final String CAMEL = System.getenv().getOrDefault("BF_CAMEL", HOME.resolve(".jbang/bin/camel").toString());
    static final Path BASE = FLOWS.getParent();
    static final Path DISABLED = BASE.resolve("disabled");
    static final Path EVENTS = BASE.resolve("events");   // Ereignis-Laufzeit (beyondflow-events), Flow-IDs beginnen mit "event-"
    static final Path GITDIR = BASE.resolve("history.git");
    static final Path LOG = FLOWS.getParent().resolve("logs/runtime.log");
    static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");
    static final String ACTIVE = ".camel.yaml";

    public static void main(String[] args) throws Exception {
        Files.createDirectories(FLOWS);
        Files.createDirectories(DISABLED);
        if (!Files.exists(GITDIR)) run(BASE.toFile(), 20, "git", "--git-dir=" + GITDIR, "--work-tree=" + BASE, "init", "-q");
        int port = Integer.parseInt(System.getenv().getOrDefault("BF_PORT", "8090"));
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        s.createContext("/api/flows", BeyondFlowApi::flows);
        s.createContext("/api/status", ex -> send(ex, 200, "text/plain", run(FLOWS.toFile(), 60, CAMEL, "get", "route")));
        s.createContext("/api/logs", ex -> send(ex, 200, "text/plain",
            run(FLOWS.toFile(), 20, "tail", "-n", "1000", LOG.toString())));
        s.createContext("/api/runtime/restart", ex -> {
            if (!ex.getRequestMethod().equals("POST")) { send(ex, 405, "text/plain", "Methode nicht erlaubt"); return; }
            String r = restartRuntime();
            send(ex, 200, "text/plain", r.isBlank() ? "Laufzeit wird neu gestartet" : r.trim());
        });
        s.createContext("/api/resources", ex -> {
            try { send(ex, 200, "application/json", Res.json()); }
            catch (Exception e) { send(ex, 500, "text/plain", "Fehler: " + e); }
        });
        s.createContext("/api/version", ex -> {
            Path v = BASE.resolve("VERSION");
            send(ex, 200, "text/plain", Files.exists(v) ? new String(Files.readAllBytes(v), StandardCharsets.UTF_8).trim() : "unbekannt");
        });
        for (String c : new String[]{ "/api/insights", "/api/check", "/api/pause", "/api/resume" }) s.createContext(c, Ops::handle);
        s.createContext("/api/sapgw/", SapGw::handle);
        s.createContext("/api/ingest/servicenow", Ingest::handle);   // oeffentlich ueber bf-edge (HTTPS 443)
        s.createContext("/api/events/", Events::handle);
        // Pruefabfragen ins CRM (nur lesend): /api/crm/check/{typ}/{key} -> Flow crm-check auf 127.0.0.1:8091
        s.createContext("/api/crm/check/", ex -> {
            if (!ex.getRequestMethod().equals("GET")) { send(ex, 405, "text/plain", "Methode nicht erlaubt"); return; }
            String rest = ex.getRequestURI().getRawPath().substring("/api/crm/check/".length());
            if (!rest.matches("(auftrag|account|kontakt|extern|sapqueue)/[A-Za-z0-9:._-]{1,100}")) {
                send(ex, 400, "application/json", "{\"found\":false,\"fehler\":\"Erlaubt: /api/crm/check/auftrag|account|kontakt|extern/<Schluessel>\"}");
                return;
            }
            String[] r = Ops.http(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:8091/check/" + rest)).GET());
            if (r[0].equals("0")) { send(ex, 503, "application/json", "{\"found\":false,\"fehler\":\"Laufzeit nicht erreichbar\"}"); return; }
            send(ex, Integer.parseInt(r[0]), "application/json; charset=utf-8", r[1]);
        });
        s.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
        s.start();
        System.out.println("BeyondFlow API auf 127.0.0.1:" + port + ", Flows in " + FLOWS);
    }

    static void flows(HttpExchange ex) throws IOException {
        try {
            String[] p = ex.getRequestURI().getPath().replaceAll("/+$", "").split("/");
            String m = ex.getRequestMethod();
            if (p.length == 3 && m.equals("GET")) { send(ex, 200, "application/json", list()); return; }
            if (p.length < 4 || !ID.matcher(p[3]).matches()) { send(ex, 400, "text/plain", "Ungueltige Flow-ID (erlaubt: a-z, 0-9, -)"); return; }
            String id = p[3];
            Path on = home(id).resolve(id + ACTIVE), off = DISABLED.resolve(id + ACTIVE);
            if (p.length == 4 && m.equals("GET")) {
                Path f = Files.exists(on) ? on : off;
                if (!Files.exists(f)) { send(ex, 404, "text/plain", "nicht gefunden"); return; }
                send(ex, 200, "text/yaml", new String(Files.readAllBytes(f), StandardCharsets.UTF_8));
            } else if (p.length == 4 && m.equals("PUT")) {
                byte[] body = readAll(ex.getRequestBody());
                if (body.length == 0 || body.length > 2_000_000) { send(ex, 400, "text/plain", "Leerer oder zu grosser Flow"); return; }
                Files.deleteIfExists(off);
                Files.write(on, body);
                commit("Flow " + id + " gespeichert");
                restartRuntime();
                send(ex, 200, "text/plain", "Flow " + id + " gespeichert, Laufzeit startet neu");
            } else if (p.length == 5 && p[4].equals("start") && m.equals("POST")) {
                if (!Files.exists(off)) { send(ex, 404, "text/plain", "Kein deaktivierter Flow " + id); return; }
                Files.move(off, on, StandardCopyOption.REPLACE_EXISTING);
                commit("Flow " + id + " aktiviert");
                restartRuntime();
                send(ex, 200, "text/plain", "Flow " + id + " aktiviert, Laufzeit startet neu");
            } else if (p.length == 5 && p[4].equals("stop") && m.equals("POST")) {
                String inUse = usedBy(on, id);
                if (inUse != null) { send(ex, 409, "text/plain", inUse); return; }
                if (Files.exists(on)) Files.move(on, off, StandardCopyOption.REPLACE_EXISTING);
                commit("Flow " + id + " deaktiviert");
                restartRuntime();
                send(ex, 200, "text/plain", "Flow " + id + " deaktiviert, Laufzeit startet neu");
            } else send(ex, 405, "text/plain", "Methode nicht erlaubt");
        } catch (Exception e) {
            send(ex, 500, "text/plain", "Fehler: " + e.getMessage());
        }
    }

    /** Ordner eines aktiven Flows: Ereignis-Flows ("event-...") laufen in der eigenen Ereignis-Laufzeit. */
    static Path home(String id) { return id.startsWith("event-") ? EVENTS : FLOWS; }

    static String trigger(Path f) {
        try {
            String y = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
            if (y.contains("routeTemplate:")) return "connector";
            if (y.contains("salesforce:subscribe") || y.contains("platform-http:")) return "event";
            if (y.contains("timer:")) return "timer";
        } catch (IOException e) { /* egal */ }
        return "other";
    }

    static String list() throws IOException {
        TreeMap<String, String> rows = new TreeMap<>();
        Files.createDirectories(EVENTS);
        for (Path dir : new Path[]{ DISABLED, FLOWS, EVENTS }) {
            boolean act = !dir.equals(DISABLED);
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*" + ACTIVE)) {
                for (Path f : ds) {
                    String n = f.getFileName().toString();
                    String id = n.substring(0, n.length() - ACTIVE.length());
                    rows.put(id, "{\"id\":\"" + id + "\",\"active\":" + act + ",\"trigger\":\"" + trigger(f) + "\",\"modified\":\""
                        + Instant.ofEpochMilli(Files.getLastModifiedTime(f).toMillis()) + "\"}");
                }
            }
        }
        return "[" + String.join(",", rows.values()) + "]";
    }

    // Neustart statt Hot-Reload: Hot-Reload verliert die Konnektor-Vorlagen (routeTemplates) aus anderen Dateien
    static String restartRuntime() {
        String r = run(BASE.toFile(), 30, "systemctl", "--user", "restart", "--no-block", "beyondflow-runtime");
        if (Files.exists(HOME.resolve(".config/systemd/user/beyondflow-events.service")))
            run(BASE.toFile(), 30, "systemctl", "--user", "restart", "--no-block", "beyondflow-events");
        return r;
    }

    /** Verhindert das Deaktivieren eines Konnektors, den ein aktiver Flow noch benutzt. */
    static String usedBy(Path file, String id) throws IOException {
        if (!Files.exists(file)) return null;
        String y = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        java.util.regex.Matcher t = Pattern.compile("routeTemplate:\\s*\\n\\s+id:\\s*\"?([\\w-]+)").matcher(y);
        List<String> users = new ArrayList<>();
        while (t.find()) {
            String tpl = t.group(1);
            for (Path dir : new Path[]{ FLOWS, EVENTS }) try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*" + ACTIVE)) {
                for (Path f : ds) {
                    if (f.equals(file)) continue;
                    if (new String(Files.readAllBytes(f), StandardCharsets.UTF_8).contains("kamelet:" + tpl)) {
                        String n = f.getFileName().toString();
                        users.add(n.substring(0, n.length() - ACTIVE.length()) + " (" + tpl + ")");
                    }
                }
            }
        }
        return users.isEmpty() ? null : "Konnektor " + id + " wird noch benutzt von: " + String.join(", ", users) + ". Erst diese Flows deaktivieren.";
    }

    static void commit(String msg) {
        String g = "--git-dir=" + GITDIR, w = "--work-tree=" + BASE;
        EVENTS.toFile().mkdirs(); run(BASE.toFile(), 20, "git", g, w, "add", "-A", "flows", "disabled", "events");
        run(BASE.toFile(), 20, "git", g, w, "-c", "user.name=BeyondFlow", "-c", "user.email=beyondflow@localhost", "commit", "-q", "-m", msg);
    }

    static String run(File dir, int timeoutSec, String... cmd) {
        try {
            Process pr = new ProcessBuilder(cmd).directory(dir).redirectErrorStream(true).start();
            byte[] out = readAll(pr.getInputStream());
            pr.waitFor(timeoutSec, TimeUnit.SECONDS);
            return new String(out, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "Fehler bei " + cmd[0] + ": " + e.getMessage();
        }
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[8192]; int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        return bo.toByteArray();
    }

    static void send(HttpExchange ex, int code, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream o = ex.getResponseBody()) { o.write(b); }
    }

    // =====================================================================
    // Betriebsassistent: Log-Analyse, Verbindungspruefung, Pausieren, KI
    // =====================================================================

    // ---------- SAP-Gateway (Reiter "SAP-Gateway" im Studio) ----------
    // Status und Anhalten/Fortsetzen ueber die Admin-Schnittstelle des Gateways (Token bleibt auf der VM),
    // Start/Stopp des Dienstes ueber einen eingeschraenkten SSH-Zugang (erzwungener Befehl auf der SAP-VM).
    static class SapGw {
        static final Path KEY = HOME.resolve(".ssh/bf_sapctl");
        static final Path AUDIT = BASE.resolve("logs/sapgw-actions.log");

        static void handle(HttpExchange ex) throws IOException {
            String path = ex.getRequestURI().getPath().replaceAll("/+$", "");
            String m = ex.getRequestMethod();
            try {
                if (path.equals("/api/sapgw/status") && m.equals("GET")) { send(ex, 200, "application/json", status()); return; }
                if (!m.equals("POST")) { send(ex, 405, "text/plain", "Methode nicht erlaubt"); return; }
                String act = path.substring("/api/sapgw/".length());
                String r;
                switch (act) {
                    case "pause": case "resume": r = admin(act); break;
                    case "service/start": case "service/stop": case "service/restart": r = service(act.substring(8)); break;
                    default: send(ex, 404, "text/plain", "Unbekannt"); return;
                }
                audit(act, r);
                send(ex, 200, "application/json", r);
            } catch (Exception e) {
                send(ex, 500, "application/json", Ops.res(false, "Fehler: " + e, 0));
            }
        }

        static String gw() { return Ops.props().getOrDefault("bf.sap.gateway", "").replaceAll("/+$", ""); }
        static String host() { return gw().replaceAll("^https?://", "").replaceAll("[:/].*$", ""); }
        static String user() { return Ops.props().getOrDefault("bf.sap.ctl.user", "bfctl"); }

        static String[] gwCall(String method, String p) {
            java.net.http.HttpRequest.Builder b = java.net.http.HttpRequest.newBuilder(java.net.URI.create(gw() + p))
                .header("X-BF-Token", Ops.secrets().getOrDefault("BF_SAP_TOKEN", ""));
            b = method.equals("POST") ? b.POST(java.net.http.HttpRequest.BodyPublishers.noBody()) : b.GET();
            return Ops.http(b);
        }

        static String admin(String act) {
            long t0 = System.currentTimeMillis();
            String[] r = gwCall("POST", "/admin/" + act);
            if (r[0].equals("0")) return Ops.res(false, "SAP-Gateway nicht erreichbar – ist der Dienst gestartet?", System.currentTimeMillis() - t0);
            if (r[0].equals("404")) return Ops.res(false, "Das SAP-Gateway kennt diese Funktion noch nicht – Gateway-Update einspielen (sap-gateway/install-sap-gateway.sh)", 0);
            return r[1];
        }

        static String ssh(String cmd) {
            if (!Files.exists(KEY)) return "nicht-eingerichtet";
            String out = run(HOME.toFile(), 40, "ssh", "-i", KEY.toString(), "-o", "BatchMode=yes", "-o", "ConnectTimeout=5",
                "-o", "StrictHostKeyChecking=accept-new", "-o", "ServerAliveInterval=5", "-o", "ServerAliveCountMax=2",
                user() + "@" + host(), cmd).trim();
            return out;
        }

        static String service(String act) {
            long t0 = System.currentTimeMillis();
            if (!Files.exists(KEY)) return Ops.res(false, "SSH-Zugang zur SAP-VM ist nicht eingerichtet (siehe Anleitung: sap-gateway/install-sap-gateway.sh)", 0);
            String out = ssh(act);
            String st = ssh("status");
            boolean ok = act.equals("stop") ? !st.endsWith("active") || st.endsWith("inactive") : st.endsWith("active") && !st.endsWith("inactive");
            String word = act.equals("stop") ? "gestoppt" : act.equals("start") ? "gestartet" : "neu gestartet";
            return Ops.res(ok, ok ? "SAP-Gateway " + word + " (Dienst: " + lastLine(st) + ")" : "Aktion " + act + " fehlgeschlagen: " + Ops.clip(out + " / " + st, 300), System.currentTimeMillis() - t0);
        }

        static String lastLine(String s) { String[] l = s.trim().split("\\n"); return l[l.length - 1].trim(); }

        static void audit(String act, String result) {
            try {
                String line = java.time.LocalDateTime.now().withNano(0) + "  " + act + "  " + Ops.clip(result, 300) + "\n";
                Files.write(AUDIT, line.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ignore) {}
        }

        static String status() {
            StringBuilder b = new StringBuilder("{");
            // 1) Gateway (Prozess + SAP)
            long t0 = System.currentTimeMillis();
            String[] g = gwCall("GET", "/admin/status");
            long gms = System.currentTimeMillis() - t0;
            if (g[0].equals("200")) b.append("\"gateway\":{\"erreichbar\":true,\"ms\":").append(gms).append(",\"daten\":").append(g[1]).append("}");
            else if (g[0].equals("404")) b.append("\"gateway\":{\"erreichbar\":true,\"veraltet\":true,\"meldung\":\"Gateway-Version ohne Admin-Schnittstelle\"}");
            else b.append("\"gateway\":{\"erreichbar\":false,\"meldung\":").append(Ops.js(g[0].equals("0") ? (g[1].contains("ConnectException") ? "Verbindung abgelehnt – Dienst gestoppt?" : g[1].contains("Timeout") ? "Zeitüberschreitung – VM oder Netz prüfen" : Ops.clip(g[1], 200)) : "HTTP " + g[0])).append("}");
            // 2) Dienst auf der SAP-VM (SSH)
            String st = ssh("status");
            b.append(",\"dienst\":{\"eingerichtet\":").append(!st.equals("nicht-eingerichtet"))
             .append(",\"zustand\":").append(Ops.js(st.equals("nicht-eingerichtet") ? "" : lastLine(st))).append("}");
            // 3) Ausgangsqueue im CRM
            String[] q = Ops.http(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:8091/check/sapqueue/alle")).GET());
            b.append(",\"queue\":").append(q[0].equals("200") ? q[1] : "{\"found\":false,\"fehler\":" + Ops.js("HTTP " + q[0]) + "}");
            // 4) Flows
            b.append(",\"flows\":[");
            String[] fl = { "crm-orders-to-sap", "sap-status-to-crm", "connector-sap" };
            for (int i = 0; i < fl.length; i++) b.append(i > 0 ? "," : "").append("{\"id\":\"").append(fl[i]).append("\",\"aktiv\":").append(Files.exists(FLOWS.resolve(fl[i] + ACTIVE))).append("}");
            b.append("]");
            // 5) letzte Aktionen
            List<String> acts = new ArrayList<>();
            try { List<String> all = Files.readAllLines(AUDIT, StandardCharsets.UTF_8); acts = all.subList(Math.max(0, all.size() - 8), all.size()); } catch (IOException ignore) {}
            b.append(",\"aktionen\":[");
            for (int i = acts.size() - 1, n = 0; i >= 0; i--, n++) b.append(n > 0 ? "," : "").append(Ops.js(acts.get(i)));
            b.append("],\"zeit\":").append(Ops.js(java.time.LocalTime.now().withNano(0).toString())).append("}");
            return b.toString();
        }
    }

    // ---------- Ereignis-Eingang ServiceNow (oeffentlich ueber bf-edge) ----------
    // ServiceNow meldet nur "Datensatz X in Tabelle Y geaendert" (keine Inhalte). Die Meldung ist signiert:
    //   X-BF-Timestamp: Unix-Sekunden, X-BF-Signature: Base64(HMAC-SHA256(Schluessel, Timestamp + "." + Body))
    // Schluessel: BF_SNOW_HMAC (Base64) in secrets.env, gleicher Wert in der ServiceNow-Systemeigenschaft.
    // Gepruefte Meldungen gehen an die Laufzeit (127.0.0.1:8091/ingest/snow) und von dort ins Kafka-Topic snow.events.
    static class Ingest {
        static final Path LOGF = BASE.resolve("logs/ingest.log");
        static final java.util.concurrent.atomic.AtomicLong ACCEPTED = new java.util.concurrent.atomic.AtomicLong(),
            REJECTED = new java.util.concurrent.atomic.AtomicLong(), FAILED = new java.util.concurrent.atomic.AtomicLong();
        static volatile String lastAccepted, lastRejected;
        static final Pattern SYSID = Pattern.compile("[0-9a-f]{32}");
        static final Set<String> TABLES = Set.of("customer_account", "customer_contact");
        static final Set<String> OPS = Set.of("insert", "update", "delete");

        static String field(String json, String name) {
            java.util.regex.Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"\\\\]{0,64})\"").matcher(json);
            return m.find() ? m.group(1) : null;
        }

        static void handle(HttpExchange ex) throws IOException {
            String ip = ex.getRequestHeaders().getFirst("X-Forwarded-For");
            if (ip == null) ip = ex.getRemoteAddress().getAddress().getHostAddress();
            if (!ex.getRequestMethod().equals("POST")) { send(ex, 405, "text/plain", "nur POST"); return; }
            byte[] raw = ex.getRequestBody().readNBytes(16385);
            if (raw.length > 16384) { reject(ex, ip, 413, "zu gross"); return; }
            String body = new String(raw, StandardCharsets.UTF_8);
            String key = Ops.secrets().get("BF_SNOW_HMAC");
            if (key == null || key.isBlank()) { reject(ex, ip, 503, "Signaturschluessel BF_SNOW_HMAC fehlt"); return; }
            String ts = ex.getRequestHeaders().getFirst("X-BF-Timestamp"), sig = ex.getRequestHeaders().getFirst("X-BF-Signature");
            if (ts == null || sig == null) { reject(ex, ip, 401, "Signatur fehlt"); return; }
            long t;
            try { t = Long.parseLong(ts.trim()); } catch (NumberFormatException e) { reject(ex, ip, 401, "Zeitstempel ungueltig"); return; }
            if (Math.abs(System.currentTimeMillis() / 1000 - t) > 300) { reject(ex, ip, 401, "Zeitstempel ausserhalb 5 Min."); return; }
            try {
                javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
                mac.init(new javax.crypto.spec.SecretKeySpec(Base64.getDecoder().decode(key.trim()), "HmacSHA256"));
                byte[] exp = mac.doFinal((ts.trim() + "." + body).getBytes(StandardCharsets.UTF_8));
                byte[] got = Base64.getDecoder().decode(sig.trim());
                if (!java.security.MessageDigest.isEqual(exp, got)) { reject(ex, ip, 401, "Signatur falsch"); return; }
            } catch (Exception e) { reject(ex, ip, 401, "Signatur nicht pruefbar"); return; }
            String table = field(body, "table"), sysId = field(body, "sys_id"), op = field(body, "operation");
            if (table == null || !TABLES.contains(table) || sysId == null || !SYSID.matcher(sysId).matches() || op == null || !OPS.contains(op)) {
                reject(ex, ip, 400, "Inhalt ungueltig"); return;
            }
            String ev = "{\"table\":\"" + table + "\",\"sys_id\":\"" + sysId + "\",\"operation\":\"" + op
                + "\",\"source\":\"servicenow\",\"received\":\"" + Instant.now().toString() + "\"}";
            String[] r = Ops.http(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:8091/ingest/snow"))
                .header("Content-Type", "application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString(ev)));
            if (!r[0].equals("200")) {
                FAILED.incrementAndGet();
                log(ip, "FEHLER " + table + " " + sysId + " " + op + " -> Laufzeit HTTP " + r[0]);
                send(ex, 503, "application/json", "{\"ok\":false,\"message\":\"voruebergehend nicht verfuegbar\"}");
                return;
            }
            ACCEPTED.incrementAndGet();
            lastAccepted = Instant.now().toString();
            log(ip, "OK " + table + " " + sysId + " " + op);
            send(ex, 202, "application/json", "{\"ok\":true}");
        }

        static void reject(HttpExchange ex, String ip, int code, String why) throws IOException {
            REJECTED.incrementAndGet();
            lastRejected = Instant.now() + " " + why;
            log(ip, "ABGELEHNT (" + code + ") " + why);
            send(ex, code, "application/json", "{\"ok\":false}");   // Grund nur im Protokoll, nicht nach aussen
        }

        static void log(String ip, String msg) {
            try {
                Files.write(LOGF, (Instant.now().toString() + "  " + Ops.clip(ip, 45) + "  " + msg + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ignore) {}
        }

        static String stats() {
            return "{\"angenommen\":" + ACCEPTED.get() + ",\"abgelehnt\":" + REJECTED.get() + ",\"fehler\":" + FAILED.get()
                + ",\"letzteAnnahme\":" + Ops.js(lastAccepted) + ",\"letzteAblehnung\":" + Ops.js(lastRejected) + "}";
        }
    }

    // ---------- Reiter "Ereignisse": Kafka-Topics, Abnehmer, Rueckstand ----------
    // Liest ueber die Kafka-Werkzeuge im Container bf-kafka (kein zusaetzlicher Client in der API).
    static class Events {
        static final String K = "/opt/kafka/bin/", BS = " --bootstrap-server 127.0.0.1:9092";
        static final Pattern TOPIC = Pattern.compile("[A-Za-z0-9._-]{1,120}");
        static volatile String cache; static volatile long cacheAt;

        static void handle(HttpExchange ex) throws IOException {
            String path = ex.getRequestURI().getPath().replaceAll("/+$", "");
            try {
                if (path.equals("/api/events/status")) {
                    boolean fresh = ex.getRequestURI().getQuery() != null && ex.getRequestURI().getQuery().contains("neu");
                    if (fresh || cache == null || System.currentTimeMillis() - cacheAt > 15000) { cache = status(); cacheAt = System.currentTimeMillis(); }
                    send(ex, 200, "application/json", cache); return;
                }
                if (path.startsWith("/api/events/replay/") && ex.getRequestMethod().equals("POST")) {
                    String t = path.substring("/api/events/replay/".length());
                    if (!TOPIC.matcher(t).matches() || !t.endsWith(".dlq")) { send(ex, 400, "text/plain", "nur Fehler-Topics (*.dlq)"); return; }
                    String r = replay(t); cache = null;
                    try { Files.write(BASE.resolve("logs/events-actions.log"), (Instant.now() + "  replay " + t + "  " + r + "\n").getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND); } catch (IOException ignore) {}
                    send(ex, 200, "application/json", r); return;
                }
                if (path.equals("/api/events/kafka")) { send(ex, 200, "application/json", kafkaView()); return; }
                if (path.startsWith("/api/events/peek/")) {
                    String t = path.substring("/api/events/peek/".length());
                    if (!TOPIC.matcher(t).matches()) { send(ex, 400, "text/plain", "Topic ungueltig"); return; }
                    send(ex, 200, "application/json", peek(t)); return;
                }
                send(ex, 404, "text/plain", "Unbekannt");
            } catch (Exception e) { send(ex, 500, "application/json", Ops.res(false, "Fehler: " + e, 0)); }
        }


        // Topics mit Nachrichtenzahl, offenen Fehlern und Abnehmergruppen; end = Ende je Topic (fuer den Reiter Kafka)
        static String topicsJson(Map<String, Long> end) {
            StringBuilder b = new StringBuilder("[");
                end.putAll(offsets(""));
                // Abnehmergruppen: GROUP TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG CONSUMER-ID HOST CLIENT-ID
                Map<String, List<String>> groups = new TreeMap<>();
                for (String l : kx(40, K + "kafka-consumer-groups.sh" + BS + " --describe --all-groups 2>/dev/null").split("\n")) {
                    String[] f = l.trim().split("\\s+");
                    if (f.length < 6 || f[0].equals("GROUP") || f[0].equals("Consumer")) continue;
                    String lag = f[5].equals("-") ? "null" : f[5];
                    boolean active = f.length > 6 && !f[6].equals("-");
                    groups.computeIfAbsent(f[1], k -> new ArrayList<>()).add("{\"gruppe\":" + Ops.js(f[0]) + ",\"position\":" + (f[3].equals("-") ? "null" : f[3])
                        + ",\"rueckstand\":" + lag + ",\"aktiv\":" + active + "}");
                    end.putIfAbsent(f[1], f[4].equals("-") ? 0L : Long.parseLong(f[4]));
                }
                int i = 0;
                for (Map.Entry<String, Long> e : end.entrySet()) {
                    b.append(i++ > 0 ? "," : "").append("{\"name\":").append(Ops.js(e.getKey())).append(",\"nachrichten\":").append(e.getValue())
                     .append(",\"fehlerTopic\":").append(e.getKey().endsWith(".dlq"))
                     .append(",\"offen\":").append(e.getKey().endsWith(".dlq") ? Math.max(0, e.getValue() - replayedUpTo(e.getKey())) : 0).append(",\"abnehmer\":[")
                     .append(String.join(",", groups.getOrDefault(e.getKey(), List.of()))).append("]}");
                }
                        return b.append("]").toString();
        }

        static String kx(int timeout, String sh) { return run(HOME.toFile(), timeout, "podman", "exec", "bf-kafka", "sh", "-c", sh); }

        static Map<String, Long> offsets(String topicArg) {
            Map<String, Long> m = new TreeMap<>();
            for (String l : kx(40, K + "kafka-get-offsets.sh" + BS + topicArg + " 2>/dev/null").split("\n")) {
                String[] f = l.trim().split(":");
                if (f.length == 3 && !f[0].startsWith("__")) try { m.merge(f[0], Long.parseLong(f[2]), Long::sum); } catch (NumberFormatException ignore) {}
            }
            return m;
        }

        static String status() {
            StringBuilder b = new StringBuilder("{");
            String ps = run(HOME.toFile(), 10, "podman", "ps", "--filter", "name=^bf-kafka$", "--format", "{{.Status}}").trim();
            boolean up = ps.startsWith("Up");
            b.append("\"kafka\":{\"laeuft\":").append(up).append(",\"zustand\":").append(Ops.js(up ? ps : (ps.isEmpty() ? "nicht gestartet" : ps))).append("}");
            b.append(",\"topics\":").append(up ? topicsJson(new TreeMap<>()) : "[]");
            // oeffentlicher Eingang
            String host = "";
            String name = "";
            try { for (String l : Files.readAllLines(HOME.resolve("beyondflow.conf"))) {
                if (l.startsWith("BF_PUBLIC_HOST=")) host = l.substring(15).replaceAll("#.*|[\"' ]", "");
                if (l.startsWith("BF_PUBLIC_NAME=")) name = l.substring(15).replaceAll("#.*|[\"' ]", "");
            } } catch (IOException ignore) {}
            if (name.isEmpty() && !host.isEmpty()) name = host.replace('.', '-') + ".sslip.io";
            host = name;
            b.append(",\"eingang\":{\"host\":").append(Ops.js(host));
            Path cert = BASE.resolve("edge/certs/fullchain.pem");
            if (!host.isEmpty() && Files.exists(cert)) {
                String c = run(HOME.toFile(), 10, "openssl", "x509", "-noout", "-enddate", "-issuer", "-in", cert.toString());
                String until = c.replaceAll("(?s).*notAfter=([^\n]*).*", "$1").trim(), iss = c.replaceAll("(?s).*issuer=([^\n]*).*", "$1").trim();
                b.append(",\"zertifikatBis\":").append(Ops.js(until)).append(",\"aussteller\":").append(Ops.js(iss))
                 .append(",\"letsencrypt\":").append(iss.contains("Let's Encrypt"));
            }
            String edge = run(HOME.toFile(), 10, "podman", "ps", "--filter", "name=^bf-edge$", "--format", "{{.Status}}").trim();
            b.append(",\"edge\":").append(Ops.js(edge.isEmpty() ? "nicht gestartet" : edge)).append(",\"statistik\":").append(Ingest.stats());
            List<String> last = new ArrayList<>();
            try { List<String> all = Files.readAllLines(Ingest.LOGF, StandardCharsets.UTF_8); last = all.subList(Math.max(0, all.size() - 8), all.size()); } catch (IOException ignore) {}
            b.append(",\"protokoll\":[");
            for (int i = last.size() - 1, n = 0; i >= 0; i--, n++) b.append(n > 0 ? "," : "").append(Ops.js(last.get(i)));
            b.append("]},\"zeit\":").append(Ops.js(Instant.now().toString().substring(11, 19))).append("}");
            return b.toString();
        }

        // Fehler-Topic erneut einspielen: alle seit dem letzten Einspielen hinzugekommenen Ereignisse zurueck ins Ursprungs-Topic.
        // Der Stand (bis zu welchem Offset eingespielt) steht in config/replay-offsets.properties und wird erst nach erfolgreichem Schreiben fortgeschrieben.
        static final Path REPLAY = BASE.resolve("config/replay-offsets.properties");
        static synchronized Properties replayState() {
            Properties p = new Properties();
            try (Reader r = Files.newBufferedReader(REPLAY)) { p.load(r); } catch (IOException ignore) {}
            return p;
        }
        static long replayedUpTo(String dlq) { try { return Long.parseLong(replayState().getProperty(dlq, "0")); } catch (NumberFormatException e) { return 0; } }

        static synchronized String replay(String dlq) {
            long t0 = System.currentTimeMillis();
            String target = dlq.substring(0, dlq.length() - 4);
            Long end = offsets(" --topic " + dlq).get(dlq);
            long from = replayedUpTo(dlq);
            if (end == null || end <= from) return Ops.res(true, "Nichts einzuspielen", System.currentTimeMillis() - t0);
            long n = end - from;
            String out = kx(120, K + "kafka-console-consumer.sh" + BS + " --topic " + dlq + " --partition 0 --offset " + from + " --max-messages " + n
                + " --timeout-ms 10000 --property print.key=true > /tmp/bf-replay.txt 2>/dev/null; c=$(grep -c . /tmp/bf-replay.txt); "
                + "if [ \"$c\" = \"" + n + "\" ]; then " + K + "kafka-console-producer.sh" + BS + " --topic " + target
                + " --property parse.key=true < /tmp/bf-replay.txt >/dev/null 2>&1 && echo REPLAY_OK || echo REPLAY_FEHLER; else echo REPLAY_GELESEN=$c; fi; rm -f /tmp/bf-replay.txt");
            if (!out.contains("REPLAY_OK"))
                return Ops.res(false, "Einspielen fehlgeschlagen (" + Ops.clip(out.trim(), 120) + ") - Ereignisse bleiben im Fehler-Topic", System.currentTimeMillis() - t0);
            Properties p = replayState(); p.setProperty(dlq, String.valueOf(end));
            try (Writer w = Files.newBufferedWriter(REPLAY)) { p.store(w, "BeyondFlow: bis zu diesem Offset erneut eingespielt"); } catch (IOException e) {
                return Ops.res(false, n + " eingespielt, Stand aber nicht gespeichert: " + e.getMessage(), System.currentTimeMillis() - t0);
            }
            return Ops.res(true, n + " Ereignis(se) erneut nach " + target + " eingespielt", System.currentTimeMillis() - t0);
        }


        // ---------- Reiter "Kafka": Dienste + Inhalt aller Topics (je die letzten 10 Ereignisse) ----------
        static final String[] UNITS = { "beyondflow-kafka", "beyondflow-runtime", "beyondflow-events", "beyondflow-edge", "beyondflow-api",
            "beyondflow-studio", "beyondflow-update.timer", "beyondflow-cert.timer" };
        static final Map<String, Long> SEEN_END = new HashMap<>();
        static final Map<String, String> SEEN_ITEMS = new HashMap<>();

        static synchronized String kafkaView() {
            long t0 = System.currentTimeMillis();
            StringBuilder b = new StringBuilder("{\"dienste\":[");
            // systemd-Benutzerdienste
            List<String> cmd = new ArrayList<>(List.of("systemctl", "--user", "show", "-p", "Id", "-p", "ActiveState", "-p", "SubState", "-p", "ActiveEnterTimestamp", "-p", "Description"));
            cmd.addAll(List.of(UNITS));
            String out = run(HOME.toFile(), 15, cmd.toArray(new String[0]));
            int n = 0;
            for (String blk : out.split("\n\\s*\n")) {
                Map<String, String> m = new HashMap<>();
                for (String l : blk.split("\n")) { int i = l.indexOf('='); if (i > 0) m.put(l.substring(0, i), l.substring(i + 1).trim()); }
                if (!m.containsKey("Id")) continue;
                b.append(n++ > 0 ? "," : "").append("{\"dienst\":").append(Ops.js(m.get("Id"))).append(",\"beschreibung\":").append(Ops.js(m.getOrDefault("Description", "")))
                 .append(",\"zustand\":").append(Ops.js(m.getOrDefault("ActiveState", "?"))).append(",\"detail\":").append(Ops.js(m.getOrDefault("SubState", "")))
                 .append(",\"seit\":").append(Ops.js(m.getOrDefault("ActiveEnterTimestamp", ""))).append("}");
            }
            // SAP-Gateway (andere VM, ueber HTTP)
            String[] g = SapGw.gwCall("GET", "/admin/status");
            String gz = g[0].equals("200") ? (g[1].contains("\"paused\":true") ? "angehalten" : "active") : g[0].equals("0") ? "nicht erreichbar" : "HTTP " + g[0];
            b.append(n > 0 ? "," : "").append("{\"dienst\":\"sap-gateway (sap-bridge)\",\"beschreibung\":\"SAP-Gateway auf der SAP-VM\",\"zustand\":").append(Ops.js(gz))
             .append(",\"detail\":\"\",\"seit\":\"\"}");
            b.append("]");
            String ps = run(HOME.toFile(), 10, "podman", "ps", "--filter", "name=^bf-kafka$", "--format", "{{.Status}}").trim();
            if (!ps.startsWith("Up")) return b.append(",\"topics\":[],\"kafka\":").append(Ops.js(ps.isEmpty() ? "nicht gestartet" : ps)).append("}").toString();
            Map<String, Long> end = new TreeMap<>();
            String topics = topicsJson(end);
            // nur Topics neu lesen, deren Ende sich seit dem letzten Aufruf geaendert hat (parallel, ein Aufruf)
            List<String> changed = new ArrayList<>();
            for (Map.Entry<String, Long> e : end.entrySet()) if (!e.getValue().equals(SEEN_END.get(e.getKey()))) changed.add(e.getKey());
            if (!changed.isEmpty()) {
                StringBuilder sh = new StringBuilder();
                for (String t : changed) {
                    long e = end.get(t);
                    if (e == 0) { SEEN_END.put(t, 0L); SEEN_ITEMS.put(t, "[]"); continue; }
                    sh.append("( ").append(K).append("kafka-console-consumer.sh").append(BS).append(" --topic ").append(t).append(" --partition 0 --offset ").append(Math.max(0, e - 10))
                      .append(" --max-messages ").append(Math.min(10, e)).append(" --timeout-ms 8000 --property print.timestamp=true --property print.key=true --property print.offset=true")
                      .append(" > /tmp/bfv_").append(t).append(".txt 2>/dev/null ) & ");
                }
                if (sh.length() > 0) {
                    sh.append("wait; for f in /tmp/bfv_*.txt; do echo \"@@TOPIC ${f#/tmp/bfv_}\"; cat \"$f\"; rm -f \"$f\"; done");
                    String res = kx(60, sh.toString());
                    for (String part : res.split("@@TOPIC ")) {
                        int nl = part.indexOf('\n'); if (nl < 0) continue;
                        String t = part.substring(0, nl).replaceAll("\\.txt$", "").trim();
                        if (!end.containsKey(t)) continue;
                        SEEN_ITEMS.put(t, items(part.substring(nl + 1)));
                        SEEN_END.put(t, end.get(t));
                    }
                }
            }
            StringBuilder ev = new StringBuilder("{");
            int i = 0;
            for (String t : end.keySet()) ev.append(i++ > 0 ? "," : "").append(Ops.js(t)).append(":").append(SEEN_ITEMS.getOrDefault(t, "[]"));
            ev.append("}");
            return b.append(",\"kafka\":").append(Ops.js(ps)).append(",\"topics\":").append(topics).append(",\"ereignisse\":").append(ev)
                    .append(",\"ms\":").append(System.currentTimeMillis() - t0).append(",\"zeit\":").append(Ops.js(Instant.now().toString().substring(11, 19))).append("}").toString();
        }

        // Ausgabe des Konsolen-Konsumenten -> JSON-Liste, neuestes zuerst (mehrzeilige Werte werden zusammengefuegt)
        static String items(String out) {
            List<String> recs = new ArrayList<>(), items = new ArrayList<>();
            for (String l : out.split("\n")) {
                if (l.startsWith("CreateTime:") || recs.isEmpty()) recs.add(l); else recs.set(recs.size() - 1, recs.get(recs.size() - 1) + " " + l.trim());
            }
            for (String l : recs) {
                String[] f = l.split("\t", 4);
                if (f.length < 4 || !f[0].startsWith("CreateTime:")) continue;
                String zeit = "";
                try { zeit = Instant.ofEpochMilli(Long.parseLong(f[0].substring(11))).toString(); } catch (NumberFormatException ignore) {}
                items.add(0, "{\"zeit\":" + Ops.js(zeit) + ",\"offset\":" + Ops.js(f[1].replace("Offset:", "")) + ",\"schluessel\":" + Ops.js(f[2]) + ",\"wert\":" + Ops.js(Ops.clip(f[3], 2000)) + "}");
            }
            return "[" + String.join(",", items) + "]";
        }

        static String peek(String topic) {
            Long end = offsets(" --topic " + topic).get(topic);
            if (end == null || end == 0) return "{\"topic\":" + Ops.js(topic) + ",\"ereignisse\":[]}";
            long from = Math.max(0, end - 10);
            String out = kx(40, K + "kafka-console-consumer.sh" + BS + " --topic " + topic + " --partition 0 --offset " + from
                + " --max-messages 10 --timeout-ms 6000 --property print.timestamp=true --property print.key=true --property print.offset=true 2>/dev/null");
            List<String> items = new ArrayList<>();
            // mehrzeilige Werte (formatiertes JSON) wieder zu einer Zeile je Ereignis zusammenfuegen
            List<String> recs = new ArrayList<>();
            for (String l : out.split("\n")) {
                if (l.startsWith("CreateTime:") || recs.isEmpty()) recs.add(l); else recs.set(recs.size() - 1, recs.get(recs.size() - 1) + " " + l.trim());
            }
            for (String l : recs) {
                // CreateTime:1764...  Offset:12  Schluessel  Wert  (durch Tabulatoren getrennt)
                String[] f = l.split("\t", 4);
                if (f.length < 4 || !f[0].startsWith("CreateTime:")) continue;
                String zeit = "";
                try { zeit = Instant.ofEpochMilli(Long.parseLong(f[0].substring(11))).toString(); } catch (NumberFormatException ignore) {}
                items.add(0, "{\"zeit\":" + Ops.js(zeit) + ",\"offset\":" + Ops.js(f[1].replace("Offset:", "")) + ",\"schluessel\":" + Ops.js(f[2]) + ",\"wert\":" + Ops.js(Ops.clip(f[3], 2000)) + "}");
            }
            return "{\"topic\":" + Ops.js(topic) + ",\"ende\":" + end + ",\"ereignisse\":[" + String.join(",", items) + "]}";
        }
    }

    static class Ops {
        static final java.time.format.DateTimeFormatter TS = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        static final Pattern LINE = Pattern.compile("^(\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d)\\.\\d+\\s+(INFO|WARN|ERROR|DEBUG|TRACE)\\s+\\d+ --- \\[[^\\]]*\\]\\s+(\\S+)\\s*:\\s?(.*)$");
        static final int WINDOW_MIN = 30;
        static final String[][] SYSTEMS = {
            // id, Anzeigename, Marker in Flows (fuer Pausieren)
            { "salesforce", "Salesforce", "kamelet:salesforce-|salesforce:subscribe" },
            { "sap", "SAP S/4HANA", "kamelet:sap-salesorder" },
            { "servicenow", "ServiceNow", "kamelet:servicenow-" },
            { "crm", "BeyondCRM-Datenbank", null },
            { "runtime", "BeyondFlow-Laufzeit", null },
        };

        static class Ev {
            java.time.LocalDateTime t; String level, logger, msg; StringBuilder full = new StringBuilder();
            String text() { return full.toString(); }
        }

        static class Finding {
            String system, name, status = "idle", title = "", summary = "", cause = "";
            java.time.LocalDateTime lastOk, lastErr; int errors, oks;
            List<String> evidence = new ArrayList<>(), recs = new ArrayList<>();
            List<String[]> actions = new ArrayList<>(); // label, method, url, confirm
        }

        static Map<String, String> readKv(Path p) {
            Map<String, String> m = new HashMap<>();
            try {
                for (String l : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                    int i = l.indexOf('=');
                    if (i > 0 && !l.trim().startsWith("#")) m.put(l.substring(0, i).trim(), l.substring(i + 1).trim());
                }
            } catch (IOException e) { /* fehlt */ }
            return m;
        }
        static Map<String, String> secrets() { return readKv(BASE.resolve("config/secrets.env")); }
        static Map<String, String> props() { return readKv(BASE.resolve("config/application.properties")); }

        static List<Ev> parse(int maxLines) throws IOException {
            List<Ev> evs = new ArrayList<>();
            if (!Files.exists(LOG)) return evs;
            List<String> all = Files.readAllLines(LOG, StandardCharsets.UTF_8);
            Ev cur = null;
            for (String l : all.subList(Math.max(0, all.size() - maxLines), all.size())) {
                java.util.regex.Matcher m = LINE.matcher(l);
                if (m.matches()) {
                    cur = new Ev();
                    cur.t = java.time.LocalDateTime.parse(m.group(1), TS);
                    cur.level = m.group(2); cur.logger = m.group(3); cur.msg = m.group(4);
                    cur.full.append(l);
                    evs.add(cur);
                } else if (cur != null && cur.full.length() < 4000) {
                    cur.full.append('\n').append(l);
                }
            }
            return evs;
        }

        static String fmt(java.time.LocalDateTime t) { return t == null ? null : t.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")); }
        static String clip(String s, int n) { s = s.replaceAll("\\s+", " ").trim(); return s.length() > n ? s.substring(0, n) + " …" : s; }
        static boolean has(String s, String re) { return Pattern.compile(re, Pattern.CASE_INSENSITIVE).matcher(s).find(); }

        static boolean paused(String sys) { return Files.exists(BASE.resolve("paused-" + sys + ".txt")); }

        static String insights() throws IOException {
            java.time.LocalDateTime since = java.time.LocalDateTime.now().minusMinutes(WINDOW_MIN);
            List<Ev> evs = new ArrayList<>();
            for (Ev e : parse(6000)) if (!e.t.isBefore(since)) evs.add(e);
            String gw = props().getOrDefault("bf.sap.gateway", "http://sap-gateway");
            String gwHost = gw.replaceFirst("^https?://", "");

            Map<String, Finding> f = new LinkedHashMap<>();
            for (String[] s : SYSTEMS) { Finding x = new Finding(); x.system = s[0]; x.name = s[1]; f.put(s[0], x); }
            List<Finding> data = new ArrayList<>();
            Map<String, Finding> dataByKey = new LinkedHashMap<>();
            int starts = 0; String missingKamelet = null; boolean startFailed = false;

            for (Ev e : evs) {
                String t = e.text(), m = e.msg;
                boolean bad = e.level.equals("WARN") || e.level.equals("ERROR");
                // --- fachliche Datenprobleme (keine Stoerung)
                String dkey = null, dmsg = null;
                if (has(m, "hat keine SAP-(Material|Kunden)nummer|SAP-Konfiguration unvollst|SAP-Fehler \\(technisch: N\\)")) { dkey = "sap"; dmsg = m; }
                else if (has(m, "^Opportunity .*\\{.*\"ok\"\\s*:\\s*false|^Opportunity .*kein CRM-Auftrag")) { dkey = "salesforce"; dmsg = m; }
                else if (has(t, "ORA-00001")) { dkey = "crm"; dmsg = "Dublette im CRM: " + clip(t.replaceAll("(?s).*?(ORA-00001[^\\n]*).*", "$1"), 160); }
                if (dkey != null) {
                    Finding d = dataByKey.computeIfAbsent(dkey, k -> { Finding n = new Finding(); n.system = "data-" + k; n.status = "warn"; return n; });
                    d.errors++; d.lastErr = e.t;
                    String ev = fmt(e.t) + "  " + clip(dmsg, 220);
                    if (d.evidence.stream().noneMatch(x -> x.substring(10).equals(ev.substring(10)))) d.evidence.add(ev);
                    continue;
                }
                // --- Laufzeit
                if (has(m, "Apache Camel \\(JBang\\) [\\d.]+ is starting")) starts++;
                if (has(t, "Error starting Camel")) startFailed = true;
                java.util.regex.Matcher km = Pattern.compile("Cannot find Kamelet with name: ([\\w-]+)").matcher(t);
                if (km.find() && has(t, "Failure creating route from template|Error starting Camel|vetoed")) missingKamelet = km.group(1);
                if (has(m, "Routes startup \\(total")) { startFailed = false; missingKamelet = null; mark(f.get("runtime"), e, true); }
                if (startFailed && e.level.equals("ERROR") && has(m, "Error starting Camel")) mark(f.get("runtime"), e, false);

                // --- Salesforce
                if (has(t, "Salesforce-Anmeldung fehlgeschlagen|Salesforce-Abfrage: HTTP|-> Salesforce fehlgeschlagen|salesforce\\.com.*(Exception|refused|timed out)|Failed to start component salesforce|Error subscribing|Error handshaking")) {
                    if (!has(t, "TimerConsumer")) mark(f.get("salesforce"), e, false);
                } else if (has(m, "Salesforce-Account|Salesforce-Kontakt|-> Salesforce (angelegt|aktualisiert)|Salesforce-Opportunity aktualisiert|-> CRM-Auftrag|Salesforce-Ereignis")) mark(f.get("salesforce"), e, true);

                // --- SAP
                if (has(t, "SAP-Gateway: |SAP-Fehler \\(technisch: Y\\)|SAP-Statusabgleich uebersprungen") || (bad && t.contains(gwHost) && !has(t, "TimerConsumer"))) mark(f.get("sap"), e, false);
                else if (has(m, "-> SAP \\d+|bereits SAP \\d+|ERP-Status .* -> ")) mark(f.get("sap"), e, true);

                // --- ServiceNow
                if (has(m, "neuer Versuch in 3 s")) { /* Wiederholung, kein Befund */ }
                else if (has(m, "^ServiceNow \\w+: HTTP \\d+") || (bad && has(t, "service-now\\.com") && !has(t, "TimerConsumer"))) mark(f.get("servicenow"), e, false);
                else if (e.logger.startsWith("snow-") && has(m, "-> CRM ")) mark(f.get("servicenow"), e, true);

                // --- CRM-Datenbank
                if (bad && has(t, "ORA-(?!00001)\\d{5}|Cannot create PoolableConnectionFactory|Cannot get a connection|IO Error") && !has(t, "TimerConsumer")) mark(f.get("crm"), e, false);
                else if (has(m, "-> CRM (INSERT|UPDATE|UNVERAENDERT)|Auftraege gelesen|-> SAP \\d+|-> CRM-Auftrag")) mark(f.get("crm"), e, true);
            }

            // --- Bewertung je System
            String rtState = run(BASE.toFile(), 10, "systemctl", "--user", "is-active", "beyondflow-runtime").trim();
            for (Finding x : f.values()) {
                if (x.lastErr != null && (x.lastOk == null || x.lastErr.isAfter(x.lastOk))) x.status = "error";
                else if (x.errors > 0) x.status = "warn";
                else if (x.oks > 0) x.status = "ok";
                diagnose(x, gw);
            }
            Finding rt = f.get("runtime");
            if (missingKamelet != null) {
                rt.status = "error"; rt.title = "Laufzeit startet nicht: Baustein „" + missingKamelet + "“ fehlt";
                rt.summary = "Ein aktiver Flow benutzt den Konnektor-Baustein " + missingKamelet + ", der Konnektor ist aber deaktiviert. Camel bricht den Start komplett ab – kein Flow läuft.";
                String conn = connectorFor(missingKamelet);
                if (conn != null) {
                    rt.recs.add("Konnektor " + conn + " wieder aktivieren.");
                    rt.actions.add(new String[]{ "Konnektor " + conn + " aktivieren", "POST", "/api/flows/" + conn + "/start", "" });
                } else rt.recs.add("Den Flow mit dem Baustein " + missingKamelet + " deaktivieren oder den passenden Konnektor wieder anlegen.");
            } else if (!rtState.equals("active")) {
                rt.status = "error"; rt.title = "Laufzeit läuft nicht (" + clip(rtState, 40) + ")";
                rt.summary = "Der Dienst beyondflow-runtime ist nicht aktiv. Es werden keine Flows ausgeführt.";
                rt.recs.add("Laufzeit neu starten; falls sie sofort wieder stoppt, im Log die letzte Fehlermeldung prüfen.");
            } else if (starts > 4) {
                rt.status = "warn"; rt.title = "Häufige Neustarts (" + starts + " in " + WINDOW_MIN + " Min.)";
                rt.summary = "Die Laufzeit wurde oft neu gestartet. Jeder Deploy startet sie neu – bei vielen Neustarts ohne Deploy deutet das auf einen Startfehler hin.";
                rt.recs.add("Im Log nach „Error starting Camel“ suchen.");
            } else if (rt.status.equals("idle") || rt.status.equals("ok")) {
                rt.status = "ok"; rt.title = "Laufzeit aktiv"; rt.summary = starts > 0 ? starts + " Start(s) in den letzten " + WINDOW_MIN + " Minuten, zuletzt erfolgreich." : "Läuft ohne Neustart.";
            }
            if (Files.exists(HOME.resolve(".config/systemd/user/beyondflow-events.service"))) {
                String ev = run(BASE.toFile(), 10, "systemctl", "--user", "is-active", "beyondflow-events").trim();
                if (!ev.equals("active")) {
                    if (rt.status.equals("ok") || rt.status.equals("idle")) rt.status = "warn";
                    rt.recs.add("Die Ereignis-Laufzeit (Salesforce-Ereignisse) ist " + ev + " – meist kann sie sich nicht bei Salesforce anmelden. Die Hauptlaufzeit ist davon nicht betroffen; der Timer holt Opportunities alle 15 Min. nach.");
                } else rt.summary = rt.summary + " Ereignis-Laufzeit aktiv.";
            }
            rt.actions.add(new String[]{ "Laufzeit neu starten", "POST", "/api/runtime/restart", "Camel-Laufzeit neu starten? Alle Flows sind ca. 20 Sekunden angehalten." });

            // --- Datenfindings
            for (Map.Entry<String, Finding> d : dataByKey.entrySet()) {
                Finding x = d.getValue();
                x.name = "Datenqualität " + f.get(d.getKey()).name;
                x.title = x.errors + " fachliche Meldung(en)";
                x.summary = "Keine Störung: einzelne Datensätze wurden wegen fehlender oder doppelter Stammdaten abgelehnt.";
                if (d.getKey().equals("sap")) x.recs.add("Fehlende SAP-Zuordnungen im CRM unter „Externe IDs“ (System SAP_S4) pflegen, dann „An SAP übergeben“ erneut klicken.");
                if (d.getKey().equals("salesforce")) x.recs.add("Account zuerst synchronisieren lassen bzw. Produktcode in Salesforce und Artikel-Nr. im CRM angleichen.");
                if (d.getKey().equals("crm")) x.recs.add("Doppelte Namen/Orte im CRM bereinigen oder im Quellsystem unterscheidbar machen.");
                while (x.evidence.size() > 5) x.evidence.remove(0);
                data.add(x);
            }

            StringBuilder sb = new StringBuilder("{\"window\":" + WINDOW_MIN + ",\"generated\":\"" + fmt(java.time.LocalDateTime.now())
                + "\",\"aiAvailable\":" + (secrets().get("BF_AI_KEY") != null) + ",\"findings\":[");
            List<Finding> out = new ArrayList<>(f.values()); out.addAll(data);
            for (int i = 0; i < out.size(); i++) { if (i > 0) sb.append(','); sb.append(toJson(out.get(i))); }
            return sb.append("]}").toString();
        }

        static void mark(Finding x, Ev e, boolean ok) {
            if (ok) { x.oks++; x.lastOk = e.t; return; }
            x.errors++; x.lastErr = e.t;
            java.util.regex.Matcher km = Pattern.compile("(Salesforce-Anmeldung fehlgeschlagen[^\\n]*|HTTP operation failed[^\\n]*|ORA-\\d{5}[^\\n]*|Cannot find Kamelet[^\\n]*|Connect to [^\\n]*)").matcher(e.text());
            String best = (e.msg.startsWith("Failed delivery") || e.msg.startsWith("Error starting")) && km.find() ? km.group(1) : e.msg;
            String line = fmt(e.t) + "  " + clip(best.isEmpty() ? e.text() : best, 240);
            String cause = e.text();
            if (x.evidence.isEmpty() || !x.evidence.get(x.evidence.size() - 1).substring(10).equals(line.substring(10))) x.evidence.add(line);
            while (x.evidence.size() > 4) x.evidence.remove(0);
            x.cause = cause;
        }

        static void diagnose(Finding x, String gw) {
            String c = x.cause == null ? "" : x.cause;
            boolean err = x.status.equals("error"), warn = x.status.equals("warn");
            String since = x.lastOk == null ? "seit mindestens " + WINDOW_MIN + " Min. kein Erfolg" : "letzter Erfolg " + fmt(x.lastOk);
            String[] sys = Arrays.stream(SYSTEMS).filter(s -> s[0].equals(x.system)).findFirst().get();
            switch (x.system) {
                case "salesforce":
                    if (err || warn) {
                        if (has(c, "invalid_client_id")) { x.title = "Salesforce lehnt die Anmeldung ab: Consumer Key unbekannt"; x.recs.add("Consumer Key in ~/beyondflow/config/secrets.env (BF_SF_CLIENT_ID) mit dem Verbraucherschlüssel der External Client App vergleichen."); }
                        else if (has(c, "no client credentials user")) { x.title = "Salesforce: kein Benutzer für Client Credentials"; x.recs.add("External Client App → Richtlinien: „Flow für Client-Anmeldeinformationen aktivieren“ und „Ausführen als“ setzen; ebenso im Reiter Einstellungen unter Flow-Aktivierung."); }
                        else if (has(c, "invalid_client\\b|invalid client credentials")) { x.title = "Salesforce: Consumer Secret falsch"; x.recs.add("BF_SF_CLIENT_SECRET in secrets.env neu setzen."); }
                        else if (has(c, "INVALID_FIELD|No such column")) { x.title = "Salesforce: Feld existiert nicht"; x.recs.add("Die im Mapping verwendeten Felder in Salesforce anlegen (z. B. BeyondCRM_Auftrag__c, SAP_Auftrag__c, ERP_Status__c an der Opportunity) oder das Mapping anpassen."); }
                        else if (has(c, "HTTP 5\\d\\d|refused|timed out|UnknownHost|Connect to")) { x.title = "Salesforce ist nicht erreichbar"; x.recs.add("Salesforce-Status auf status.salesforce.com prüfen."); x.recs.add("Bis zur Behebung die Salesforce-Flows pausieren, damit das Log sauber bleibt."); }
                        else { x.title = "Salesforce meldet Fehler"; x.recs.add("Details unten im Nachweis bzw. im Log (Filter „Salesforce“)."); }
                    }
                    break;
                case "sap":
                    if (err || warn) {
                        if (has(c, "Gateway nicht erreichbar|Connect to .*failed|refused|timed out|SAP-Gateway: ")) { x.title = "SAP-Gateway antwortet nicht"; x.recs.add("Auf der VM sap-bridge: sudo systemctl status sap-gateway – bei Bedarf sudo systemctl restart sap-gateway."); x.recs.add("OCI Security List: Ingress TCP 8095 aus 10.0.0.0/16 muss vorhanden sein."); }
                        else if (has(c, "Token fehlt|HTTP 401")) { x.title = "SAP-Gateway lehnt den Token ab"; x.recs.add("BF_SAP_TOKEN (secrets.env) mit GW_TOKEN in /etc/sap-bridge.env auf sap-bridge abgleichen."); }
                        else if (has(c, "SAP: |technisch: Y")) { x.title = "SAP-System antwortet nicht (RFC)"; x.recs.add("SAP-System bzw. SAProuter prüfen; das Gateway selbst läuft. Aufträge werden mit Wiederholung erneut versucht."); }
                        else { x.title = "SAP meldet Fehler"; x.recs.add("Details im Nachweis bzw. im Log (Filter „SAP“)."); }
                    }
                    break;
                case "servicenow":
                    if (err || warn) {
                        if (has(c, "HTTP 401|HTTP 403")) { x.title = "ServiceNow lehnt die Anmeldung ab"; x.recs.add("BF_SNOW_USER / BF_SNOW_PASSWORD in secrets.env prüfen."); }
                        else if (has(c, "snow_adc|hibernat|HTTP 50[0-9]")) { x.title = "ServiceNow-Instanz antwortet mit Serverfehler"; x.recs.add("Developer-Instanzen schlafen nach Inaktivität: Instanz im Browser öffnen bzw. auf developer.servicenow.com aufwecken."); x.recs.add("Tritt der Fehler nur vereinzelt auf, ist keine Aktion nötig – der nächste Abruf holt die Daten nach."); }
                        else { x.title = "ServiceNow meldet Fehler"; x.recs.add("Details im Nachweis bzw. im Log (Filter „ServiceNow“)."); }
                    }
                    break;
                case "crm":
                    if (err || warn) {
                        if (has(c, "ORA-28001|ORA-28002")) { x.title = "Passwort des DB-Benutzers abgelaufen"; x.recs.add("ALTER USER BEYONDFLOW IDENTIFIED BY … als ADMIN; Profil SERVICE_ACCOUNT zuweisen."); }
                        else if (has(c, "ORA-01017")) { x.title = "DB-Anmeldung abgelehnt"; x.recs.add("BF_CRM_DB_PASSWORD in secrets.env mit dem Passwort von BEYONDFLOW abgleichen."); }
                        else if (has(c, "ORA-00942|ORA-06550|PLS-")) { x.title = "Datenbankobjekt fehlt oder ist ungültig"; x.recs.add("Paket/Views im Schema SIEBEL prüfen (VALID?) und Grants für BEYONDFLOW."); }
                        else if (has(c, "ORA-125\\d\\d|IO Error|Cannot create PoolableConnectionFactory|Cannot get a connection")) { x.title = "CRM-Datenbank nicht erreichbar"; x.recs.add("Autonomous Database in der OCI-Konsole prüfen (gestoppt?); Wallet unter ~/beyondflow/wallet."); }
                        else { x.title = "Datenbank meldet Fehler"; x.recs.add("Details im Nachweis bzw. im Log (Filter „ORA-“)."); }
                    }
                    break;
                default: break;
            }
            if (!x.system.equals("runtime")) {
                if (x.status.equals("ok")) { x.title = x.name + " arbeitet normal"; x.summary = x.oks + " erfolgreiche Verarbeitung(en), zuletzt " + fmt(x.lastOk) + "."; }
                else if (x.status.equals("idle")) { x.title = "Keine Aktivität"; x.summary = "In den letzten " + WINDOW_MIN + " Minuten weder Erfolge noch Fehler im Log."; }
                else if (err) x.summary = x.errors + " Fehler, zuletzt " + fmt(x.lastErr) + "; " + since + ".";
                else x.summary = x.errors + " Fehler, danach wieder erfolgreich (" + since + ") – vermutlich vorübergehend.";
                if (x.status.equals("error") && x.system.equals("crm")) x.recs.add("Solange die DB nicht erreichbar ist, laufen alle Flows ins Leere – Ursache zuerst beheben.");
            }
            if (!x.system.equals("crm") && !x.system.equals("runtime")) x.actions.add(new String[]{ x.name + " prüfen", "POST", "/api/check/" + x.system, "" });
            if (sys[2] != null) {
                if (paused(x.system)) { x.actions.add(new String[]{ x.name + "-Flows fortsetzen", "POST", "/api/resume/" + x.system, "" }); x.recs.add(0, "Die Flows für " + x.name + " sind pausiert."); }
                else if (!x.status.equals("ok") && !x.status.equals("idle")) x.actions.add(new String[]{ x.name + "-Flows pausieren", "POST", "/api/pause/" + x.system, "Alle Flows, die " + x.name + " benutzen, pausieren? Die Laufzeit startet dafür neu." });
            }
        }

        static String connectorFor(String template) throws IOException {
            for (Path dir : new Path[]{ DISABLED, FLOWS, EVENTS }) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*" + ACTIVE)) {
                    for (Path p : ds) {
                        String y = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                        if (Pattern.compile("routeTemplate:\\s*\\n\\s+id:\\s*\"?" + Pattern.quote(template) + "\"?\\s").matcher(y).find()) {
                            String n = p.getFileName().toString(); return n.substring(0, n.length() - ACTIVE.length());
                        }
                    }
                }
            }
            return null;
        }

        static String toJson(Finding x) {
            StringBuilder b = new StringBuilder("{");
            b.append("\"system\":").append(js(x.system)).append(",\"name\":").append(js(x.name)).append(",\"status\":").append(js(x.status))
             .append(",\"title\":").append(js(x.title)).append(",\"summary\":").append(js(x.summary))
             .append(",\"lastOk\":").append(js(fmt(x.lastOk))).append(",\"lastErr\":").append(js(fmt(x.lastErr)))
             .append(",\"errors\":").append(x.errors).append(",\"oks\":").append(x.oks).append(",\"paused\":").append(paused(x.system));
            b.append(",\"evidence\":["); for (int i = 0; i < x.evidence.size(); i++) b.append(i > 0 ? "," : "").append(js(x.evidence.get(i)));
            b.append("],\"recommendations\":["); for (int i = 0; i < x.recs.size(); i++) b.append(i > 0 ? "," : "").append(js(x.recs.get(i)));
            b.append("],\"actions\":[");
            for (int i = 0; i < x.actions.size(); i++) { String[] a = x.actions.get(i);
                b.append(i > 0 ? "," : "").append("{\"label\":").append(js(a[0])).append(",\"method\":").append(js(a[1])).append(",\"url\":").append(js(a[2])).append(",\"confirm\":").append(js(a[3])).append("}"); }
            return b.append("]}").toString();
        }

        static String js(String s) {
            if (s == null) return "null";
            StringBuilder b = new StringBuilder("\"");
            for (char c : s.toCharArray()) {
                if (c == '"' || c == '\\') b.append('\\').append(c);
                else if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                else b.append(c);
            }
            return b.append('"').toString();
        }

        // ---------- aktive Pruefungen ----------
        static final java.net.http.HttpClient HC = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build();

        static String[] http(java.net.http.HttpRequest.Builder rb) {
            try {
                java.net.http.HttpResponse<String> r = HC.send(rb.timeout(java.time.Duration.ofSeconds(25)).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
                return new String[]{ String.valueOf(r.statusCode()), r.body() };
            } catch (Exception e) { return new String[]{ "0", e.getClass().getSimpleName() + ": " + e.getMessage() }; }
        }
        static String form(String... kv) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < kv.length; i += 2) b.append(i > 0 ? "&" : "").append(kv[i]).append('=').append(java.net.URLEncoder.encode(kv[i + 1] == null ? "" : kv[i + 1], StandardCharsets.UTF_8));
            return b.toString();
        }

        static String check(String sys) throws IOException {
            Map<String, String> s = secrets(), p = props();
            long t0 = System.currentTimeMillis();
            String[] r; String ok, what;
            switch (sys) {
                case "salesforce": {
                    String url = p.getOrDefault("bf.sf.url", "");
                    if (url.isEmpty()) return res(false, "bf.sf.url fehlt in application.properties", 0);
                    r = http(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url + "/services/oauth2/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(form("grant_type", "client_credentials", "client_id", s.get("BF_SF_CLIENT_ID"), "client_secret", s.get("BF_SF_CLIENT_SECRET")))));
                    boolean good = r[0].equals("200") && r[1].contains("access_token");
                    return res(good, good ? "Anmeldung bei Salesforce erfolgreich" : "Salesforce: HTTP " + r[0] + " – " + clip(r[1].replaceAll("\"access_token\"\\s*:\\s*\"[^\"]*\"", "\"access_token\":\"***\""), 300), System.currentTimeMillis() - t0);
                }
                case "sap": {
                    String gw = p.getOrDefault("bf.sap.gateway", "");
                    if (gw.isEmpty()) return res(false, "bf.sap.gateway fehlt in application.properties", 0);
                    r = http(java.net.http.HttpRequest.newBuilder(java.net.URI.create(gw + "/health")).header("X-BF-Token", s.getOrDefault("BF_SAP_TOKEN", "")).GET());
                    boolean good = r[0].equals("200") && has(r[1], "\"ok\"\\s*:\\s*true");
                    return res(good, good ? "SAP-Gateway und SAP-System erreichbar (RFC_PING)" : (r[0].equals("0") ? "SAP-Gateway nicht erreichbar: " + clip(r[1], 200) : "SAP: " + clip(r[1], 300)), System.currentTimeMillis() - t0);
                }
                case "servicenow": {
                    String inst = p.getOrDefault("bf.snow.instance", "");
                    if (inst.isEmpty()) try {
                        String y = new String(Files.readAllBytes(FLOWS.resolve("connector-servicenow" + ACTIVE)), StandardCharsets.UTF_8);
                        java.util.regex.Matcher m = Pattern.compile("name: instance[\\s\\S]{0,200}?defaultValue:\\s*\"?([\\w-]+)").matcher(y);
                        if (m.find()) inst = m.group(1);
                    } catch (IOException e) { /* Standard */ }
                    String auth = Base64.getEncoder().encodeToString((s.getOrDefault("BF_SNOW_USER", "") + ":" + s.getOrDefault("BF_SNOW_PASSWORD", "")).getBytes(StandardCharsets.UTF_8));
                    r = http(java.net.http.HttpRequest.newBuilder(java.net.URI.create("https://" + inst + ".service-now.com/api/now/table/sys_user?sysparm_limit=1&sysparm_fields=sys_id"))
                        .header("Authorization", "Basic " + auth).header("Accept", "application/json").GET());
                    boolean good = r[0].equals("200");
                    return res(good, good ? "ServiceNow " + inst + " erreichbar, Anmeldung OK" : "ServiceNow " + inst + ": HTTP " + r[0] + " – " + clip(r[1].replaceAll("<[^>]+>", " "), 200), System.currentTimeMillis() - t0);
                }
                default: return res(false, "Unbekanntes System: " + sys, 0);
            }
        }
        static String res(boolean ok, String msg, long ms) { return "{\"ok\":" + ok + ",\"message\":" + js(msg) + ",\"ms\":" + ms + "}"; }

        // ---------- Pausieren / Fortsetzen ----------
        static String pause(String sys) throws IOException {
            String marker = Arrays.stream(SYSTEMS).filter(x -> x[0].equals(sys)).map(x -> x[2]).findFirst().orElse(null);
            if (marker == null) return "Fuer dieses System gibt es nichts zu pausieren";
            List<String> ids = new ArrayList<>();
            for (Path dir : new Path[]{ FLOWS, EVENTS }) try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*" + ACTIVE)) {
                for (Path p : ds) {
                    String y = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                    if (has(y, marker) && !y.contains("routeTemplate:")) {
                        String n = p.getFileName().toString(); ids.add(n.substring(0, n.length() - ACTIVE.length()));
                    }
                }
            }
            if (ids.isEmpty()) return "Keine aktiven Flows fuer " + sys;
            for (String id : ids) Files.move(home(id).resolve(id + ACTIVE), DISABLED.resolve(id + ACTIVE), StandardCopyOption.REPLACE_EXISTING);
            Path f = BASE.resolve("paused-" + sys + ".txt");
            Set<String> all = new TreeSet<>(ids);
            if (Files.exists(f)) all.addAll(Files.readAllLines(f));
            Files.write(f, all);
            commit("Flows pausiert (" + sys + "): " + String.join(", ", ids));
            restartRuntime();
            return "Pausiert: " + String.join(", ", ids) + " – Laufzeit startet neu";
        }
        static String resume(String sys) throws IOException {
            Path f = BASE.resolve("paused-" + sys + ".txt");
            if (!Files.exists(f)) return "Fuer " + sys + " ist nichts pausiert";
            List<String> back = new ArrayList<>();
            for (String id : Files.readAllLines(f)) {
                id = id.trim();
                if (!ID.matcher(id).matches()) continue;
                Path off = DISABLED.resolve(id + ACTIVE);
                if (Files.exists(off)) { Files.move(off, home(id).resolve(id + ACTIVE), StandardCopyOption.REPLACE_EXISTING); back.add(id); }
            }
            Files.delete(f);
            commit("Flows fortgesetzt (" + sys + "): " + String.join(", ", back));
            restartRuntime();
            return "Fortgesetzt: " + (back.isEmpty() ? "–" : String.join(", ", back)) + " – Laufzeit startet neu";
        }

        // ---------- KI-Zusammenfassung (optional, Claude API) ----------
        static String ai() throws IOException {
            Map<String, String> s = secrets();
            String key = s.get("BF_AI_KEY");
            if (key == null || key.isEmpty()) return res(false, "Kein BF_AI_KEY in secrets.env – KI-Zusammenfassung nicht eingerichtet", 0);
            String model = s.getOrDefault("BF_AI_MODEL", "claude-sonnet-5-5");
            String findings = insights();
            StringBuilder lines = new StringBuilder();
            java.time.LocalDateTime since = java.time.LocalDateTime.now().minusMinutes(WINDOW_MIN);
            int n = 0;
            List<Ev> evs = parse(6000);
            for (int i = evs.size() - 1; i >= 0 && n < 120; i--) {
                Ev e = evs.get(i);
                if (e.t.isBefore(since)) break;
                if (e.level.equals("WARN") || e.level.equals("ERROR") || has(e.msg, "->")) { lines.insert(0, e.t.format(TS) + " " + e.level + " " + e.logger + ": " + clip(e.msg, 300) + "\n"); n++; }
            }
            String prompt = "Du bist der Betriebsassistent der Integrationsplattform BeyondFlow (Apache Camel). Sie verbindet BeyondCRM (Oracle), "
                + "Salesforce, ServiceNow und SAP S/4HANA (über ein SAP-Gateway). Unten stehen die automatische Regelauswertung (JSON) und die "
                + "letzten Log-Einträge der vergangenen " + WINDOW_MIN + " Minuten.\n\nAntworte auf Deutsch, knapp (höchstens 12 Zeilen), mit den "
                + "Abschnitten **Lage**, **Wahrscheinliche Ursache** und **Empfohlene Schritte** (nummeriert). Unterscheide Störungen von "
                + "fachlichen Datenproblemen. Erfinde keine Fakten, die nicht aus den Daten hervorgehen.\n\n=== Regelauswertung ===\n" + findings
                + "\n\n=== Log ===\n" + lines;
            String body = "{\"model\":" + js(model) + ",\"max_tokens\":900,\"messages\":[{\"role\":\"user\",\"content\":" + js(prompt) + "}]}";
            long t0 = System.currentTimeMillis();
            String[] r = http(java.net.http.HttpRequest.newBuilder(java.net.URI.create("https://api.anthropic.com/v1/messages"))
                .header("x-api-key", key).header("anthropic-version", "2023-06-01").header("content-type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)));
            if (!r[0].equals("200")) return res(false, "KI-Dienst: HTTP " + r[0] + " – " + clip(r[1], 300), System.currentTimeMillis() - t0);
            java.util.regex.Matcher m = Pattern.compile("\"text\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").matcher(r[1]);
            StringBuilder text = new StringBuilder();
            while (m.find()) text.append(unjs(m.group(1)));
            return res(text.length() > 0, text.length() > 0 ? text.toString() : "Leere Antwort des KI-Dienstes", System.currentTimeMillis() - t0);
        }
        static String unjs(String s) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c != '\\' || i + 1 >= s.length()) { b.append(c); continue; }
                char e = s.charAt(++i);
                switch (e) {
                    case 'n': b.append('\n'); break; case 't': b.append('\t'); break; case 'r': break;
                    case 'u': b.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16)); i += 4; break;
                    default: b.append(e);
                }
            }
            return b.toString();
        }

        static void handle(HttpExchange ex) throws IOException {
            String[] p = ex.getRequestURI().getPath().replaceAll("/+$", "").split("/");
            String m = ex.getRequestMethod();
            try {
                if (p.length == 3 && p[2].equals("insights") && m.equals("GET")) { send(ex, 200, "application/json", insights()); return; }
                if (p.length == 4 && p[2].equals("insights") && p[3].equals("ai") && m.equals("POST")) { send(ex, 200, "application/json", ai()); return; }
                if (!m.equals("POST") || p.length != 4 || !p[3].matches("salesforce|sap|servicenow")) { send(ex, 400, "text/plain", "Ungueltige Anfrage"); return; }
                switch (p[2]) {
                    case "check": send(ex, 200, "application/json", check(p[3])); return;
                    case "pause": send(ex, 200, "application/json", res(true, pause(p[3]), 0)); return;
                    case "resume": send(ex, 200, "application/json", res(true, resume(p[3]), 0)); return;
                    default: send(ex, 404, "text/plain", "unbekannt");
                }
            } catch (Exception e) {
                send(ex, 500, "application/json", res(false, "Fehler: " + e, 0));
            }
        }
    }

    // =====================================================================
    // Ressourcen: CPU und Speicher je BeyondFlow-Prozess (aus /proc, Messfenster 500 ms)
    // =====================================================================
    static class Res {
        static long[] cpuTicks(long pid) {
            try {
                String s = new String(Files.readAllBytes(Paths.get("/proc/" + pid + "/stat")), StandardCharsets.UTF_8);
                String[] f = s.substring(s.lastIndexOf(')') + 2).split(" ");
                return new long[]{ Long.parseLong(f[11]) + Long.parseLong(f[12]), Long.parseLong(f[19]) };
            } catch (Exception e) { return null; }
        }
        static long rssKb(long pid) {
            try {
                for (String l : Files.readAllLines(Paths.get("/proc/" + pid + "/status")))
                    if (l.startsWith("VmRSS:")) return Long.parseLong(l.replaceAll("\\D+", ""));
            } catch (Exception e) { /* weg */ }
            return 0;
        }
        static long totalTicks() {
            try { String[] f = Files.readAllLines(Paths.get("/proc/stat")).get(0).trim().split("\\s+");
                long t = 0; for (int i = 1; i < f.length; i++) t += Long.parseLong(f[i]); return t; } catch (Exception e) { return 0; }
        }
        static long idleTicks() {
            try { String[] f = Files.readAllLines(Paths.get("/proc/stat")).get(0).trim().split("\\s+");
                return Long.parseLong(f[4]) + Long.parseLong(f[5]); } catch (Exception e) { return 0; }
        }
        static String role(String cmd) {
            String exe = cmd.split(" ")[0];
            boolean java = exe.endsWith("/java") || exe.equals("java");
            if (cmd.contains("BeyondFlowApi")) return java ? "API (Studio-Backend)" : null;
            if (java && cmd.contains("camel") && cmd.contains(" run")) return cmd.contains("beyondflow/events") ? "Ereignis-Laufzeit (Camel)" : "Laufzeit (Camel)";
            if (cmd.startsWith("nginx: master")) return "Webserver (nginx)";
            if (cmd.startsWith("nginx: worker")) return "Webserver (nginx Worker)";
            if (cmd.contains("jbang") && cmd.contains("camel")) return "Laufzeit (Starter)";
            return null;
        }
        static String json() throws Exception {
            Map<Long, String[]> procs = new TreeMap<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(Paths.get("/proc"))) {
                for (Path p : ds) {
                    String n = p.getFileName().toString();
                    if (!n.matches("\\d+")) continue;
                    try {
                        String cmd = new String(Files.readAllBytes(p.resolve("cmdline")), StandardCharsets.UTF_8).replace('\0', ' ').trim();
                        String r = role(cmd);
                        if (r != null) procs.put(Long.parseLong(n), new String[]{ r, cmd });
                    } catch (Exception e) { /* Prozess beendet */ }
                }
            }
            Map<Long, long[]> t0 = new HashMap<>();
            for (Long pid : procs.keySet()) t0.put(pid, cpuTicks(pid));
            long tot0 = totalTicks(), idle0 = idleTicks();
            Thread.sleep(500);
            long tot1 = totalTicks(), idle1 = idleTicks();
            int cpus = Runtime.getRuntime().availableProcessors();
            double hz = 100.0, secs = 0.5;
            long uptimeTicks = (long) (Double.parseDouble(new String(Files.readAllBytes(Paths.get("/proc/uptime"))).split(" ")[0]) * hz);
            StringBuilder b = new StringBuilder("{\"processes\":[");
            boolean first = true;
            for (Map.Entry<Long, String[]> e : procs.entrySet()) {
                long[] a = t0.get(e.getKey()), c = cpuTicks(e.getKey());
                if (a == null || c == null) continue;
                double cpu = (c[0] - a[0]) / (hz * secs) * 100.0;
                long ageSec = (long) ((uptimeTicks - c[1]) / hz);
                if (!first) b.append(','); first = false;
                b.append("{\"pid\":").append(e.getKey()).append(",\"role\":").append(Ops.js(e.getValue()[0]))
                 .append(",\"cpu\":").append(String.format(Locale.ROOT, "%.1f", cpu))
                 .append(",\"memMb\":").append(rssKb(e.getKey()) / 1024)
                 .append(",\"ageSec\":").append(ageSec).append('}');
            }
            long memTotal = 0, memAvail = 0;
            for (String l : Files.readAllLines(Paths.get("/proc/meminfo"))) {
                if (l.startsWith("MemTotal:")) memTotal = Long.parseLong(l.replaceAll("\\D+", "")) / 1024;
                if (l.startsWith("MemAvailable:")) memAvail = Long.parseLong(l.replaceAll("\\D+", "")) / 1024;
            }
            double hostCpu = tot1 > tot0 ? 100.0 * (1 - (double) (idle1 - idle0) / (tot1 - tot0)) : 0;
            String load = new String(Files.readAllBytes(Paths.get("/proc/loadavg"))).split(" ")[0];
            b.append("],\"host\":{\"cpus\":").append(cpus).append(",\"cpu\":").append(String.format(Locale.ROOT, "%.1f", hostCpu))
             .append(",\"load1\":").append(load).append(",\"memTotalMb\":").append(memTotal).append(",\"memUsedMb\":").append(memTotal - memAvail).append("}}");
            return b.toString();
        }
    }
}
