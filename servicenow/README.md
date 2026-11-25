# ServiceNow: Ereignisse an BeyondFlow

ServiceNow meldet jede Änderung an Accounts und Kontakten sofort an BeyondFlow. Der stündliche Abgleich bleibt als Absicherung.

```
ServiceNow (Business Rule, async)
  -> HTTPS POST https://<IP>/ingest/servicenow   {table, sys_id, operation} + HMAC-Signatur
  -> BeyondFlow API (prüft Signatur, Zeitstempel ±5 Min., Tabelle, sys_id)
  -> Flow snow-events-in -> Kafka-Topic snow.events
  -> Flow snow-events-to-crm (liest den Datensatz über die Table API nach) -> BeyondCRM
     Fehler -> Kafka-Topic snow.events.dlq
```

Die Meldung enthält **keine Inhalte**, nur „Datensatz X in Tabelle Y wurde geändert“. Eine gefälschte Meldung könnte also höchstens ein erneutes Lesen auslösen.

## Einrichtung (einmalig, als Administrator)

1. **Systemeigenschaften** (`sys_properties`):

   | Name | Typ | Wert |
   |---|---|---|
   | `beyondflow.events.url` | string | `https://<öffentliche IP der BeyondFlow-VM>/ingest/servicenow` |
   | `beyondflow.events.hmac_key` | password2 | Wert von `BF_SNOW_HMAC` aus `~/beyondflow/config/secrets.env` auf der BeyondFlow-VM |

   Den Schlüssel auf der VM anzeigen: `grep BF_SNOW_HMAC ~/beyondflow/config/secrets.env` (nur den Teil nach `=` kopieren).
2. **Script Include** `BeyondFlowEvents` (global, „Accessible from: This application scope only“, nicht clientseitig aufrufbar): Inhalt von [`BeyondFlowEvents.js`](BeyondFlowEvents.js).
3. **Business Rules** „BeyondFlow Ereignis“ auf `customer_account` und auf `customer_contact`:
   - Advanced, When: **async**, Insert und Update.
   - Script: [`business-rule.js`](business-rule.js).
4. **Test:** einen Account ändern. Im BeyondFlow Studio, Reiter „Ereignisse“, steigt „Angenommen“. Im Log steht „Account … -> CRM aktualisiert (servicenow)“.

Fehlersuche in ServiceNow: Systemprotokoll, Meldungen mit „BeyondFlow:“.

## Alternative mit Lizenz: Stream Connect for Apache Kafka

Mit Integration Hub und Stream Connect kann ServiceNow selbst direkt in Kafka schreiben (Workflow Studio, Schritt „Kafka Producer“). Dann entfällt der HTTPS-Eingang, dafür muss Kafka von ServiceNow aus erreichbar sein (TLS/SASL). Dafür sind eigene Abonnements nötig. Die BeyondFlow-Seite (Topic `snow.events`, Flow `snow-events-to-crm`) bleibt gleich.
