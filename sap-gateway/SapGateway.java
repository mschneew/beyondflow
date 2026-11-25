import com.sap.conn.jco.*;
import com.sap.conn.jco.ext.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * SAP-Gateway fuer BeyondFlow: macht SAP S/4HANA per RFC (JCo) als kleine REST-Schnittstelle
 * im privaten Netz verfuegbar. Nur Standard-BAPIs, nichts in SAP programmiert.
 *
 *   GET  /health                   RFC_PING
 *   POST /salesorder               Kundenauftrag pruefen (TESTRUN) und anlegen, Dublettenschutz ueber Kundenbestellnr.
 *   GET  /salesorder/{vbeln}/status  Status eines Kundenauftrags (BAPISDORDER_GETDETAILEDLIST)
 *
 * Jede Anfrage braucht den Header X-BF-Token (GW_TOKEN aus /etc/sap-bridge.env).
 * Antworten sind immer JSON mit "ok" (true/false); fachliche Fehler: technical=false, Verbindungsfehler: technical=true.
 */
public class SapGateway {
    static String env(String k, String d) { String v = System.getenv(k); return (v == null || v.isEmpty()) ? d : v; }
    static String need(String k) { String v = System.getenv(k); if (v == null || v.isEmpty()) throw new IllegalStateException("Umgebungsvariable fehlt: " + k); return v; }
    static void log(String s) { System.out.println(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + " " + s); System.out.flush(); }

    static class BusinessError extends Exception { BusinessError(String m) { super(m); } }

    static JCoDestination sap;
    static String token;
    // Betriebszustand fuer /admin/status (BeyondFlow Studio, Reiter SAP-Gateway)
    static final long STARTED = System.currentTimeMillis();
    static final java.io.File PAUSE_FILE = new java.io.File(System.getProperty("user.home"), "sapgateway/PAUSED");
    static volatile boolean paused = PAUSE_FILE.exists();
    static final java.util.concurrent.atomic.AtomicLong CNT_CREATED = new java.util.concurrent.atomic.AtomicLong();
    static final java.util.concurrent.atomic.AtomicLong CNT_BUSINESS = new java.util.concurrent.atomic.AtomicLong();
    static final java.util.concurrent.atomic.AtomicLong CNT_TECHNICAL = new java.util.concurrent.atomic.AtomicLong();
    static final java.util.concurrent.atomic.AtomicLong CNT_REQUESTS = new java.util.concurrent.atomic.AtomicLong();
    static volatile Map<String, Object> lastOk = null, lastErr = null;
    static String now() { return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").format(new Date()); }

    public static void main(String[] a) throws Exception {
        final Properties p = new Properties();
        p.setProperty(DestinationDataProvider.JCO_ASHOST, need("SAP_ASHOST"));
        p.setProperty(DestinationDataProvider.JCO_SYSNR, env("SAP_SYSNR", "00"));
        if (!env("SAP_ROUTER", "").isEmpty()) p.setProperty(DestinationDataProvider.JCO_SAPROUTER, env("SAP_ROUTER", ""));
        p.setProperty(DestinationDataProvider.JCO_CLIENT, need("SAP_CLIENT"));
        p.setProperty(DestinationDataProvider.JCO_LANG, env("SAP_LANG", "DE"));
        p.setProperty(DestinationDataProvider.JCO_USER, need("SAP_USER"));
        p.setProperty(DestinationDataProvider.JCO_PASSWD, need("SAP_PASSWD"));
        p.setProperty(DestinationDataProvider.JCO_PEAK_LIMIT, "2");
        Environment.registerDestinationDataProvider(new DestinationDataProvider() {
            public Properties getDestinationProperties(String n) { return "S23".equals(n) ? p : null; }
            public boolean supportsEvents() { return false; }
            public void setDestinationDataEventListener(DestinationDataEventListener l) {}
        });
        sap = JCoDestinationManager.getDestination("S23");
        token = need("GW_TOKEN");
        if (token.length() < 20) throw new IllegalStateException("GW_TOKEN ist zu kurz (mind. 20 Zeichen)");

        String bind = env("GW_BIND", "0.0.0.0");
        int port = Integer.parseInt(env("GW_PORT", "8095"));
        HttpServer s = HttpServer.create(new InetSocketAddress(bind, port), 0);
        s.createContext("/", new HttpHandler() { public void handle(HttpExchange ex) throws IOException { route(ex); } });
        s.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(2));
        s.start();
        log("SapGateway auf " + bind + ":" + port + ", SAP " + p.getProperty(DestinationDataProvider.JCO_ASHOST) + " Mandant " + p.getProperty(DestinationDataProvider.JCO_CLIENT));
    }

    static void route(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath().replaceAll("/+$", "");
        String m = ex.getRequestMethod();
        Map<String, Object> res = new LinkedHashMap<String, Object>();
        int code = 200;
        try {
            if (!token.equals(ex.getRequestHeaders().getFirst("X-BF-Token"))) {
                send(ex, 401, obj("ok", false, "technical", false, "message", "Token fehlt oder falsch"));
                return;
            }
            if (!path.startsWith("/admin")) CNT_REQUESTS.incrementAndGet();
            if (path.equals("/admin/status") && m.equals("GET")) {
                res = adminStatus();
            } else if (path.equals("/admin/pause") && m.equals("POST")) {
                paused = true; try { PAUSE_FILE.getParentFile().mkdirs(); PAUSE_FILE.createNewFile(); } catch (IOException ignore) {}
                log("ANGEHALTEN ueber /admin/pause");
                res = obj("ok", true, "paused", true, "message", "SAP-Gateway angehalten – neue Auftraege werden abgewiesen, BeyondFlow wiederholt sie spaeter");
            } else if (path.equals("/admin/resume") && m.equals("POST")) {
                paused = false; PAUSE_FILE.delete();
                log("FORTGESETZT ueber /admin/resume");
                res = obj("ok", true, "paused", false, "message", "SAP-Gateway fortgesetzt");
            } else if (paused) {
                code = 503; res = obj("ok", false, "technical", true, "paused", true, "message", "SAP-Gateway ist angehalten");
            } else if (path.equals("/health") && m.equals("GET")) {
                sap.ping();
                res = obj("ok", true, "message", "SAP erreichbar");
            } else if (path.equals("/salesorder") && m.equals("POST")) {
                Object in = Json.parse(new String(readAll(ex.getRequestBody()), StandardCharsets.UTF_8));
                if (!(in instanceof Map)) throw new BusinessError("JSON-Objekt erwartet");
                res = createSalesOrder(cast(in));
            } else if (path.matches("/salesorder/[0-9]{1,10}/status") && m.equals("GET")) {
                res = status(path.split("/")[2]);
            } else {
                code = 404; res = obj("ok", false, "technical", false, "message", "Unbekannter Pfad: " + m + " " + path);
            }
        } catch (BusinessError e) {
            log("fachlicher Fehler: " + e.getMessage());
            CNT_BUSINESS.incrementAndGet(); lastErr = obj("zeit", now(), "technisch", false, "meldung", e.getMessage());
            res = obj("ok", false, "technical", false, "message", e.getMessage());
        } catch (JCoException e) {
            boolean technical = e.getGroup() == JCoException.JCO_ERROR_COMMUNICATION || e.getGroup() == JCoException.JCO_ERROR_SYSTEM_FAILURE
                || e.getGroup() == JCoException.JCO_ERROR_RESOURCE || e.getGroup() == JCoException.JCO_ERROR_STATE_BUSY
                || e.getGroup() == JCoException.JCO_ERROR_LOGON_FAILURE;
            log("SAP-Fehler: " + e.getMessage());
            if (technical) CNT_TECHNICAL.incrementAndGet(); else CNT_BUSINESS.incrementAndGet();
            lastErr = obj("zeit", now(), "technisch", technical, "meldung", "SAP: " + e.getMessage());
            res = obj("ok", false, "technical", technical, "message", "SAP: " + e.getMessage());
        } catch (Exception e) {
            log("interner Fehler: " + e);
            res = obj("ok", false, "technical", false, "message", "Gateway: " + e);
        }
        send(ex, code, res);
    }

    // ---------------- Betrieb ----------------

    static Map<String, Object> adminStatus() {
        long t0 = System.currentTimeMillis();
        Map<String, Object> ping;
        try { sap.ping(); ping = obj("ok", true, "ms", System.currentTimeMillis() - t0, "meldung", "RFC_PING erfolgreich"); }
        catch (JCoException e) { ping = obj("ok", false, "ms", System.currentTimeMillis() - t0, "meldung", e.getMessage()); }
        return obj("ok", true, "paused", paused, "gestartet", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").format(new Date(STARTED)),
            "laufzeitSek", (System.currentTimeMillis() - STARTED) / 1000, "sapPing", ping,
            "anfragen", CNT_REQUESTS.get(), "angelegt", CNT_CREATED.get(), "fachlicheFehler", CNT_BUSINESS.get(),
            "technischeFehler", CNT_TECHNICAL.get(), "letzterErfolg", lastOk, "letzterFehler", lastErr);
    }

    // ---------------- Kundenauftrag ----------------

    static Map<String, Object> createSalesOrder(Map<String, Object> o) throws JCoException, BusinessError {
        String purchNo = str(o, "PURCH_NO_C", true), soldTo = str(o, "SOLD_TO", true);
        String vkorg = str(o, "SALES_ORG", true), vtweg = str(o, "DISTR_CHAN", true), spart = str(o, "DIVISION", true), auart = str(o, "DOC_TYPE", true);
        Object itObj = o.get("ITEMS");
        if (!(itObj instanceof List) || ((List<?>) itObj).isEmpty()) throw new BusinessError("Auftrag hat keine Positionen (ITEMS)");
        List<String[]> items = new ArrayList<String[]>();
        int n = 0;
        for (Object x : (List<?>) itObj) {
            n++;
            if (!(x instanceof Map)) throw new BusinessError("Position " + n + " ist kein Objekt");
            Map<String, Object> i = cast(x);
            String mat = str(i, "MATERIAL", false);
            if (mat == null) throw new BusinessError("Position " + n + " hat keine SAP-Materialnummer");
            String qty = i.get("REQ_QTY") == null ? "1" : String.valueOf(i.get("REQ_QTY"));
            String pos = i.get("ITM_NUMBER") == null ? String.valueOf(n * 10) : String.valueOf(i.get("ITM_NUMBER"));
            items.add(new String[]{ String.format("%06d", (int) Double.parseDouble(pos)), mat, qty });
        }
        log("Auftrag " + purchNo + " fuer Kunde " + soldTo + " (" + items.size() + " Pos.)");

        String existing = findExisting(soldTo, vkorg, purchNo);
        if (existing != null) {
            log("  bereits in SAP: " + existing);
            return obj("ok", true, "vbeln", existing, "existing", true, "message", "Bereits in SAP vorhanden (" + existing + ")");
        }
        callCreate(o, soldTo, items, true);
        String[] r = callCreate(o, soldTo, items, false);
        log("  angelegt: " + r[0]);
        CNT_CREATED.incrementAndGet(); lastOk = obj("zeit", now(), "vbeln", r[0], "bestellnr", purchNo);
        return obj("ok", true, "vbeln", r[0], "existing", false, "message", r[1]);
    }

    /** Kopfdaten: alle Felder des JSON (ausser SOLD_TO, ITEMS), die es in ORDER_HEADER_IN gibt. */
    static String[] callCreate(Map<String, Object> o, String kunnr, List<String[]> items, boolean test) throws JCoException, BusinessError {
        JCoContext.begin(sap);
        try {
            JCoFunction f = fn("BAPI_SALESORDER_CREATEFROMDAT2");
            JCoStructure h = f.getImportParameterList().getStructure("ORDER_HEADER_IN");
            for (Map.Entry<String, Object> e : o.entrySet()) {
                if (e.getKey().equals("SOLD_TO") || e.getKey().equals("ITEMS") || e.getValue() == null || e.getValue() instanceof Map || e.getValue() instanceof List) continue;
                if (!h.getMetaData().hasField(e.getKey())) throw new BusinessError("Feld " + e.getKey() + " gibt es nicht in ORDER_HEADER_IN");
                h.setValue(e.getKey(), String.valueOf(e.getValue()));
            }
            if (test) f.getImportParameterList().setValue("TESTRUN", "X");
            JCoTable pa = f.getTableParameterList().getTable("ORDER_PARTNERS");
            pa.appendRow(); pa.setValue("PARTN_ROLE", "AG"); pa.setValue("PARTN_NUMB", kunnr);
            JCoTable it = f.getTableParameterList().getTable("ORDER_ITEMS_IN");
            JCoTable sc = f.getTableParameterList().getTable("ORDER_SCHEDULES_IN");
            boolean longMat = it.getMetaData().hasField("MATERIAL_LONG");
            for (String[] i : items) {
                it.appendRow(); it.setValue("ITM_NUMBER", i[0]); it.setValue(longMat ? "MATERIAL_LONG" : "MATERIAL", i[1]);
                sc.appendRow(); sc.setValue("ITM_NUMBER", i[0]); sc.setValue("REQ_QTY", i[2]);
            }
            f.execute(sap);
            StringBuilder msgs = new StringBuilder(); boolean err = false;
            JCoTable r = f.getTableParameterList().getTable("RETURN");
            for (int k = 0; k < r.getNumRows(); k++) {
                r.setRow(k);
                String t = r.getString("TYPE");
                if ("E".equals(t) || "A".equals(t)) { err = true; msgs.append(r.getString("MESSAGE")).append("; "); }
            }
            if (err || test) fn("BAPI_TRANSACTION_ROLLBACK").execute(sap);
            if (err) throw new BusinessError((test ? "SAP-Pruefung: " : "SAP-Anlage: ") + msgs);
            if (test) return new String[]{ null, "Simulation OK" };
            JCoFunction cm = fn("BAPI_TRANSACTION_COMMIT");
            cm.getImportParameterList().setValue("WAIT", "X");
            cm.execute(sap);
            String vbeln = f.getExportParameterList().getString("SALESDOCUMENT");
            return new String[]{ vbeln, "SAP-Auftrag " + vbeln + " angelegt" };
        } finally {
            JCoContext.end(sap);
        }
    }

    static String findExisting(String kunnr, String vkorg, String purchNo) throws JCoException {
        JCoFunction f = fn("BAPI_SALESORDER_GETLIST");
        f.getImportParameterList().setValue("CUSTOMER_NUMBER", kunnr);
        f.getImportParameterList().setValue("SALES_ORGANIZATION", vkorg);
        f.execute(sap);
        JCoTable t = f.getTableParameterList().getTable("SALES_ORDERS");
        for (int k = 0; k < t.getNumRows(); k++) {
            t.setRow(k);
            if (purchNo.equals(t.getString("PURCH_NO").trim())) return t.getString("SD_DOC");
        }
        return null;
    }

    // ---------------- Status ----------------

    static Map<String, Object> status(String vbeln) throws JCoException {
        String doc = String.format("%010d", Long.parseLong(vbeln));
        JCoFunction f = fn("BAPISDORDER_GETDETAILEDLIST");
        JCoStructure v = f.getImportParameterList().getStructure("I_BAPI_VIEW");
        v.setValue("HEADER", "X"); v.setValue("STATUS_H", "X");
        JCoTable d = f.getTableParameterList().getTable("SALES_DOCUMENTS");
        d.appendRow(); d.setValue("VBELN", doc);
        f.execute(sap);
        JCoTable hd = f.getTableParameterList().getTable("ORDER_HEADERS_OUT");
        if (hd.getNumRows() == 0) return obj("ok", true, "found", false, "vbeln", doc, "message", "Auftrag " + doc + " nicht in SAP");
        JCoTable st = f.getTableParameterList().getTable("STATUSHEADERS_OUT");
        Map<String, Object> raw = new LinkedHashMap<String, Object>();
        String status = "Angelegt"; int rank = 0;
        if (st.getNumRows() > 0) {
            st.setRow(0);
            for (JCoField fld : st) {
                String name = fld.getName(), val = fld.getString() == null ? "" : fld.getString().trim();
                if (val.isEmpty()) continue;
                raw.put(name, val);
                if (!"C".equals(val)) continue;
                // C = vollstaendig: Absage > Faktura > Lieferung
                if (name.contains("REJ") && rank < 3) { status = "Storniert"; rank = 3; }
                else if ((name.contains("BILL") || name.startsWith("FKST")) && rank < 2) { status = "Fakturiert"; rank = 2; }
                else if ((name.contains("DLV") || name.contains("DELIV") || name.startsWith("LFST")) && rank < 1) { status = "Geliefert"; rank = 1; }
            }
        }
        return obj("ok", true, "found", true, "vbeln", doc, "status", status, "sap", raw);
    }

    // ---------------- Hilfen ----------------

    static JCoFunction fn(String name) throws JCoException {
        JCoFunction f = sap.getRepository().getFunction(name);
        if (f == null) throw new JCoException(JCoException.JCO_ERROR_FUNCTION_NOT_FOUND, name + " nicht gefunden");
        return f;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> cast(Object o) { return (Map<String, Object>) o; }

    static String str(Map<String, Object> o, String k, boolean required) throws BusinessError {
        Object v = o.get(k);
        String s = v == null ? null : String.valueOf(v).trim();
        if (s != null && s.isEmpty()) s = null;
        if (s == null && required) throw new BusinessError("Pflichtfeld " + k + " fehlt");
        return s;
    }

    static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[8192]; int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        return bo.toByteArray();
    }

    static void send(HttpExchange ex, int code, Map<String, Object> body) throws IOException {
        byte[] b = Json.write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, b.length);
        OutputStream o = ex.getResponseBody();
        try { o.write(b); } finally { o.close(); }
    }

    /** Minimaler JSON-Leser/-Schreiber (Java 8, keine Bibliotheken). */
    static class Json {
        final String s; int i;
        Json(String s) { this.s = s; }
        static Object parse(String s) { Json j = new Json(s); Object v = j.val(); j.ws(); if (j.i != s.length()) throw new IllegalArgumentException("JSON: Zeichen nach Ende"); return v; }
        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        Object val() {
            ws();
            if (i >= s.length()) throw new IllegalArgumentException("JSON: unerwartetes Ende");
            char c = s.charAt(i);
            if (c == '{') { i++; Map<String, Object> m = new LinkedHashMap<String, Object>(); ws();
                if (s.charAt(i) == '}') { i++; return m; }
                while (true) { ws(); String k = (String) val(); ws(); expect(':'); m.put(k, val()); ws();
                    if (s.charAt(i) == ',') { i++; continue; } expect('}'); return m; } }
            if (c == '[') { i++; List<Object> l = new ArrayList<Object>(); ws();
                if (s.charAt(i) == ']') { i++; return l; }
                while (true) { l.add(val()); ws(); if (s.charAt(i) == ',') { i++; continue; } expect(']'); return l; } }
            if (c == '"') { i++; StringBuilder b = new StringBuilder();
                while (true) { char d = s.charAt(i++);
                    if (d == '"') return b.toString();
                    if (d == '\\') { char e = s.charAt(i++);
                        switch (e) { case 'n': b.append('\n'); break; case 't': b.append('\t'); break; case 'r': b.append('\r'); break;
                            case 'b': b.append('\b'); break; case 'f': b.append('\f'); break;
                            case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                            default: b.append(e); } }
                    else b.append(d); } }
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            int st = i; while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (st == i) throw new IllegalArgumentException("JSON: unerwartetes Zeichen '" + c + "'");
            String num = s.substring(st, i);
            return num.matches("-?\\d+") ? (Object) Long.valueOf(num) : (Object) Double.valueOf(num);
        }
        void expect(char c) { if (s.charAt(i) != c) throw new IllegalArgumentException("JSON: '" + c + "' erwartet"); i++; }
        static String write(Object v) {
            if (v == null) return "null";
            if (v instanceof Boolean || v instanceof Number) return String.valueOf(v);
            if (v instanceof Map) { StringBuilder b = new StringBuilder("{"); boolean first = true;
                for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) { if (!first) b.append(','); first = false; b.append(write(String.valueOf(e.getKey()))).append(':').append(write(e.getValue())); }
                return b.append('}').toString(); }
            if (v instanceof List) { StringBuilder b = new StringBuilder("["); boolean first = true;
                for (Object x : (List<?>) v) { if (!first) b.append(','); first = false; b.append(write(x)); }
                return b.append(']').toString(); }
            String t = String.valueOf(v); StringBuilder b = new StringBuilder("\"");
            for (char c : t.toCharArray()) {
                if (c == '"' || c == '\\') b.append('\\').append(c);
                else if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                else b.append(c);
            }
            return b.append('"').toString();
        }
    }
}
