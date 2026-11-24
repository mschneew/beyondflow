#!/usr/bin/env python3
"""BeyondFlow: traegt die eigenen Konnektoren als Kamelets in den Kaoto-Katalog ein.

Sucht alle kamelets-aggregate-*.json unter ~/kaoto-web/camel-catalog und fuegt
beyondcrm-upsert und servicenow-query hinzu (vorhandene Eintraege werden ersetzt).
Die Laufzeit nutzt weiter die routeTemplate-Dateien in ~/beyondflow/flows.
"""
import base64, glob, json, os

ICON_CRM = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64">
<rect x="2" y="2" width="60" height="60" rx="14" fill="#2A2522"/>
<ellipse cx="32" cy="18" rx="17" ry="6" fill="#E8A33D"/>
<path d="M15 18v28c0 3.3 7.6 6 17 6s17-2.7 17-6V18" fill="none" stroke="#E8A33D" stroke-width="4"/>
<path d="M15 32c0 3.3 7.6 6 17 6s17-2.7 17-6" fill="none" stroke="#E8A33D" stroke-width="4"/>
<circle cx="48" cy="48" r="9" fill="#2FA088" stroke="#2A2522" stroke-width="3"/>
<path d="M44 48h8M48 44v8" stroke="#fff" stroke-width="2.5" stroke-linecap="round"/>
</svg>"""

ICON_SNOW = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64">
<rect x="2" y="2" width="60" height="60" rx="14" fill="#2A2522"/>
<rect x="11" y="14" width="42" height="36" rx="5" fill="none" stroke="#3B7FC4" stroke-width="4"/>
<path d="M11 25h42M25 25v25M39 25v25M11 37h42" stroke="#3B7FC4" stroke-width="3"/>
<circle cx="48" cy="48" r="9" fill="#E8A33D" stroke="#2A2522" stroke-width="3"/>
<path d="M45 48h6M49 45l3 3-3 3" fill="none" stroke="#2A2522" stroke-width="2.5" stroke-linecap="round"/>
</svg>"""


def icon(svg):
    return "data:image/svg+xml;base64," + base64.b64encode(svg.encode()).decode()


def kamelet(name, title, desc, ico, props, required, deps, steps):
    definition = {"title": title, "description": desc, "required": required,
                  "type": "object", "properties": props}
    return {
        "apiVersion": "camel.apache.org/v1",
        "kind": "Kamelet",
        "metadata": {
            "name": name,
            "annotations": {
                "camel.apache.org/kamelet.support.level": "Stable",
                "camel.apache.org/catalog.version": "beyondflow-1",
                "camel.apache.org/kamelet.icon": ico,
                "camel.apache.org/provider": "BeyondFlow",
                "camel.apache.org/kamelet.group": "BeyondFlow",
                "camel.apache.org/kamelet.namespace": "BeyondFlow",
            },
            "labels": {"camel.apache.org/kamelet.type": "action"},
        },
        "spec": {
            "definition": definition,
            "dependencies": deps,
            "template": {"from": {"uri": "kamelet:source", "steps": steps}},
        },
        "propertiesSchema": {"$schema": "http://json-schema.org/draft-07/schema#",
                             "type": "object", "title": title, "description": desc,
                             "required": required, "properties": props},
    }


CRM = kamelet(
    "beyondcrm-upsert", "BeyondCRM Upsert",
    "Legt im BeyondCRM einen Datensatz an oder aktualisiert ihn anhand der externen ID. "
    "Der Body ist ein JSON-Objekt mit CRM-Spalten (aus dem Mapping-Schritt).",
    icon(ICON_CRM),
    {
        "entity": {"title": "Entitaet", "description": "CRM-Entitaet (Account = S_ORG_EXT, Contact = S_CONTACT)",
                   "type": "string", "enum": ["Account", "Contact"], "default": "Account"},
        "system": {"title": "Quellsystem", "description": "Kennung des Quellsystems fuer die externe ID (S_EXT_REF)",
                   "type": "string", "default": "SNOW"},
    },
    ["entity"],
    ["camel:sql", "mvn:com.oracle.database.jdbc:ojdbc11:23.6.0.24.10"],
    [{"to": {"uri": "sql-stored:siebel.cx_bf_api.upsert(...)", "parameters": {"dataSource": "#crmdb"}}}],
)

SNOW = kamelet(
    "servicenow-query", "ServiceNow Query",
    "Liest Datensaetze aus einer ServiceNow-Tabelle (Table API). Zugangsdaten kommen aus der Laufzeit-Konfiguration.",
    icon(ICON_SNOW),
    {
        "instance": {"title": "Instanz", "description": "Name der ServiceNow-Instanz (<name>.service-now.com)",
                     "type": "string", "default": "{{bf.snow.instance}}"},
        "table": {"title": "Tabelle", "description": "z. B. customer_account oder customer_contact",
                  "type": "string", "example": "customer_account"},
        "fields": {"title": "Felder", "description": "Kommagetrennte Feldliste (sysparm_fields)", "type": "string"},
        "query": {"title": "Abfrage", "description": "Encoded Query (sysparm_query)",
                  "type": "string", "default": "ORDERBYDESCsys_updated_on"},
        "limit": {"title": "Limit", "description": "Maximale Anzahl Datensaetze", "type": "integer", "default": 50},
    },
    ["table", "fields"],
    ["camel:http"],
    [{"to": {"uri": "https://{{instance}}.service-now.com/api/now/table/{{table}}"}}],
)

ICON_SAP = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64">
<rect x="2" y="2" width="60" height="60" rx="14" fill="#2A2522"/>
<rect x="12" y="12" width="32" height="40" rx="4" fill="none" stroke="#3B7FC4" stroke-width="4"/>
<path d="M19 23h18M19 31h18M19 39h11" stroke="#3B7FC4" stroke-width="3" stroke-linecap="round"/>
<circle cx="47" cy="46" r="10" fill="#E8A33D" stroke="#2A2522" stroke-width="3"/>
<path d="M42 46l3.5 3.5L52 43" fill="none" stroke="#2A2522" stroke-width="2.8" stroke-linecap="round" stroke-linejoin="round"/>
</svg>"""

SAP_CREATE = kamelet(
    "sap-salesorder-create", "SAP Kundenauftrag anlegen",
    "Prueft (TESTRUN) und legt einen Kundenauftrag in SAP S/4HANA an (BAPI_SALESORDER_CREATEFROMDAT2) ueber das SAP-Gateway. "
    "Body: JSON mit SAP-Feldnamen (PURCH_NO_C, SOLD_TO, SALES_ORG, ..., ITEMS). Antwort: ok, vbeln, message, technical.",
    icon(ICON_SAP), {}, [], ["camel:http"],
    [{"to": {"uri": "{{bf.sap.gateway}}/salesorder"}}],
)

SAP_STATUS = kamelet(
    "sap-salesorder-status", "SAP Auftragsstatus lesen",
    "Liest den Status eines SAP-Kundenauftrags (BAPISDORDER_GETDETAILEDLIST) ueber das SAP-Gateway. "
    "Auftragsnummer in der Exchange-Property sapVbeln. Antwort: ok, found, status (Angelegt, Geliefert, Fakturiert, Storniert).",
    icon(ICON_SAP), {}, [], ["camel:http"],
    [{"toD": {"uri": "{{bf.sap.gateway}}/salesorder/${exchangeProperty.sapVbeln}/status"}}],
)

ICON_SF = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64">
<rect x="2" y="2" width="60" height="60" rx="14" fill="#2A2522"/>
<path d="M20 44h24a9 9 0 0 0 1-17.9A12 12 0 0 0 22.3 24 10 10 0 0 0 20 44z" fill="none" stroke="#3B7FC4" stroke-width="4" stroke-linejoin="round"/>
<circle cx="47" cy="47" r="9" fill="#2FA088" stroke="#2A2522" stroke-width="3"/>
<path d="M43 47h8M48 44l3 3-3 3" fill="none" stroke="#fff" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"/>
</svg>"""

SF_QUERY = kamelet(
    "salesforce-query", "Salesforce Abfrage (SOQL)",
    "Fuehrt eine SOQL-Abfrage ueber die Salesforce REST API aus. Anmeldung per OAuth Client Credentials (External Client App). Ergebnis: JSON mit records[].",
    icon(ICON_SF),
    {"soql": {"title": "SOQL", "description": "z. B. SELECT Id, Name FROM Account ORDER BY SystemModstamp DESC LIMIT 200", "type": "string"}},
    ["soql"], ["camel:http", "camel:jq"],
    [{"toD": {"uri": "${exchangeProperty.sfInstance}/services/data/v62.0/query"}}],
)

SF_UPSERT = kamelet(
    "salesforce-upsert", "Salesforce Datensatz schreiben",
    "Legt einen Salesforce-Datensatz an (Body ohne Id) oder aktualisiert ihn (Body mit Id). Antwort: ok, id, created, message.",
    icon(ICON_SF),
    {"sobject": {"title": "Objekt", "description": "Salesforce-Objekt, z. B. Account oder Opportunity", "type": "string",
                 "enum": ["Account", "Contact", "Opportunity"], "default": "Account"}},
    ["sobject"], ["camel:http", "camel:jq"],
    [{"toD": {"uri": "${exchangeProperty.sfInstance}/services/data/v62.0/sobjects/{{sobject}}"}}],
)

SF_TOKEN = kamelet(
    "salesforce-token", "Salesforce Anmeldung",
    "Holt ein Zugriffstoken per OAuth Client Credentials (wird von den anderen Salesforce-Bausteinen intern verwendet).",
    icon(ICON_SF), {}, [], ["camel:http"],
    [{"to": {"uri": "{{bf.sf.url}}/services/oauth2/token"}}],
)

root = os.path.expanduser("~/kaoto-web/camel-catalog")
files = glob.glob(os.path.join(root, "**", "kamelets-aggregate-*.json"), recursive=True)
if not files:
    raise SystemExit("Keine kamelets-aggregate-*.json unter " + root + " gefunden")
for f in files:
    with open(f, encoding="utf-8") as fh:
        data = json.load(fh)
    if not isinstance(data, dict):
        print("uebersprungen (unbekanntes Format):", f)
        continue
    data["beyondcrm-upsert"] = CRM
    data["servicenow-query"] = SNOW
    data["sap-salesorder-create"] = SAP_CREATE
    data["sap-salesorder-status"] = SAP_STATUS
    data["salesforce-query"] = SF_QUERY
    data["salesforce-upsert"] = SF_UPSERT
    data["salesforce-token"] = SF_TOKEN
    with open(f, "w", encoding="utf-8") as fh:
        json.dump(data, fh, separators=(",", ":"))
    print("Kamelets eingetragen:", os.path.relpath(f, root), "->", len(data), "Eintraege")
