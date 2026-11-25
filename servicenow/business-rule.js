/**
 * Business Rules "BeyondFlow Ereignis" auf customer_account und customer_contact
 * Wann: async, Einfuegen + Aktualisieren, Reihenfolge 1000, aktiv
 * Async: ServiceNow wartet nicht auf BeyondFlow, der Benutzer merkt nichts.
 * Faellt ein Ereignis aus, holt der stuendliche Abgleich in BeyondFlow die Aenderung nach.
 */
(function executeRule(current, previous /*null when async*/) {
    new BeyondFlowEvents().send(current, current.sys_mod_count == 0 ? 'insert' : 'update');
})(current, previous);
