/**
 * Script Include "BeyondFlowEvents" (global, nicht clientseitig aufrufbar)
 * Meldet eine Aenderung an BeyondFlow: nur Tabelle, sys_id und Art der Aenderung, keine Inhalte.
 * BeyondFlow liest den Datensatz anschliessend selbst ueber die Table API nach.
 *
 * Signatur: X-BF-Signature = Base64(HMAC-SHA256(Schluessel, X-BF-Timestamp + "." + Body))
 * Systemeigenschaften:
 *   beyondflow.events.url       https://<oeffentliche IP der BeyondFlow-VM>/ingest/servicenow
 *   beyondflow.events.hmac_key  (Typ password2) = BF_SNOW_HMAC aus ~/beyondflow/config/secrets.env
 */
var BeyondFlowEvents = Class.create();
BeyondFlowEvents.prototype = {
    initialize: function() {},

    send: function(gr, operation) {
        var url = gs.getProperty('beyondflow.events.url');
        var key = gs.getProperty('beyondflow.events.hmac_key');
        if (!url || !key) {
            gs.warn('BeyondFlow: beyondflow.events.url oder beyondflow.events.hmac_key fehlt - kein Ereignis gesendet');
            return false;
        }
        var body = JSON.stringify({ table: gr.getTableName(), sys_id: gr.getUniqueValue(), operation: operation });
        var ts = String(Math.floor(new GlideDateTime().getNumericValue() / 1000));
        var sig = new GlideCertificateEncryption().generateMac(key, 'HmacSHA256', ts + '.' + body);
        var rm = new sn_ws.RESTMessageV2();
        rm.setEndpoint(url);
        rm.setHttpMethod('post');
        rm.setHttpTimeout(10000);
        rm.setRequestHeader('Content-Type', 'application/json');
        rm.setRequestHeader('X-BF-Timestamp', ts);
        rm.setRequestHeader('X-BF-Signature', sig);
        rm.setRequestBody(body);
        var resp = rm.execute();
        var code = resp.getStatusCode();
        if (code != 202) {
            gs.warn('BeyondFlow: Ereignis ' + body + ' nicht angenommen, HTTP ' + code + ' ' + resp.getErrorMessage());
            return false;
        }
        return true;
    },

    type: 'BeyondFlowEvents'
};
