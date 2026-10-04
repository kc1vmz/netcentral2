package com.kc1vmz.netcentral.aprsobject.common;

import com.kc1vmz.netcentral.aprsobject.object.reports.APRSNetCentralReport;

public class TransceiverReport extends TransceiverRequest {

    private APRSNetCentralReport report;

    public TransceiverReport() {
        super();
    }
    public APRSNetCentralReport getReport() {
        return report;
    }
    public void setReport(APRSNetCentralReport report) {
        this.report = report;
    }
}
