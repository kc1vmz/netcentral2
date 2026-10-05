package com.kc1vmz.netcentral.accessor;

import com.kc1vmz.netcentral.operations.OperationsStore;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DataMaintenanceAccessor {
    public record AprsDataDeleted() {}
    private final OperationsStore store;
    private final ApplicationEventPublisher events;

    public DataMaintenanceAccessor(OperationsStore store, ApplicationEventPublisher events) {
        this.store = store;
        this.events = events;
    }

    public void requireSysadmin() {
        if (!store.accounts().current().role().equals("SYSADMIN"))
            throw new AccessDeniedException("System administrator required");
    }

    @Transactional
    public void deleteAprs() {
        requireSysadmin();
        store.lock();
        var jdbc = store.jdbc();
        jdbc.update("DELETE FROM resource_history WHERE object_id IN (SELECT record_key FROM domain_record WHERE kind='OBSERVED_OBJECT')");
        jdbc.update("DELETE FROM domain_record WHERE kind IN ('STATION','OBSERVED_OBJECT','FEDERATED_NET','FEDERATION_QUERY','INSTALLATION_MESSAGE')");
        jdbc.update("DELETE FROM operational_report WHERE remote=TRUE");
        jdbc.update("DELETE FROM raw_packet");
        jdbc.update("DELETE FROM station_position");
        jdbc.update("DELETE FROM net_radio_dedup");
        jdbc.update("UPDATE net_participant SET lat=NULL,lon=NULL,version=version+1 WHERE net_id IN (SELECT id FROM managed_net WHERE closed_at IS NULL)");
        jdbc.update("UPDATE managed_net SET version=version+1 WHERE closed_at IS NULL");
        events.publishEvent(new AprsDataDeleted());
    }

    @Transactional
    public void deleteNets() {
        requireSysadmin();
        store.lock();
        var jdbc = store.jdbc();
        for (String table : new String[]{"net_answer", "net_question", "net_message", "net_radio_dedup",
                "net_expected", "net_participant", "net_occurrence", "schedule_expected", "managed_net", "net_schedule"})
            jdbc.update("DELETE FROM " + table);
        jdbc.update("DELETE FROM domain_record WHERE kind IN ('NET_OPTIONS','SCHEDULE_OPTIONS')");
        jdbc.update("DELETE FROM communication_route WHERE owner_key LIKE 'NET:%' OR owner_key LIKE 'SCHEDULE:%'");
    }
}
