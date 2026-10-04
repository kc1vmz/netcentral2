package com.kc1vmz.netcentral.aprsobject.object;

import com.kc1vmz.netcentral.aprsobject.interfaces.APRSPacketInterface;

public class APRSItem implements APRSPacketInterface {
    private byte [] data;
    private byte [] header;
    private String callsignFrom;
    private String callsignTo;
    private String applicationName;
    private boolean alive;
    private String lat;
    private String lon;
    private byte dti;
    private String comment;
    private String symbolTableId;
    private String symbolTableCode;

    public byte [] getData() {
        return data;
    }
    public void setData(byte[] data) {
        this.data = data;
    }
    public byte [] getHeader() {
        return header;
    }
    public void setHeader(byte[] header) {
        this.header = header;
    }
    public String getCallsignFrom() {
        return callsignFrom;
    }
    public void setCallsignFrom(String callsignFrom) {
        this.callsignFrom = callsignFrom;
    }
    public String getCallsignTo() {
        return callsignTo;
    }
    public void setCallsignTo(String callsignTo) {
        this.callsignTo = callsignTo;
    }
    public boolean isAlive() {
        return alive;
    }
    public void setAlive(boolean alive) {
        this.alive = alive;
    }
    public String getLat() {
        return lat;
    }
    public void setLat(String lat) {
        this.lat = lat;
    }
    public String getLon() {
        if ((lon != null) && (lon.length() == 8)) {
            return "0"+lon;
        }
        return lon;
    }
    public void setLon(String lon) {
        this.lon = lon;
    }
    public byte getDti() {
        return dti;
    }
    public void setDti(byte dti) {
        this.dti = dti;
    }
    public String getComment() {
        return comment;
    }
    public void setComment(String comment) {
        this.comment = comment;
    }
    public String getApplicationName() {
        return applicationName;
    }
    public void setApplicationName(String applicationName) {
        this.applicationName = applicationName;
    }
    public String getSymbolTableId() {
        return symbolTableId;
    }
    public void setSymbolTableId(String symbolTableId) {
        this.symbolTableId = symbolTableId;
    }
    public String getSymbolTableCode() {
        return symbolTableCode;
    }
    public void setSymbolTableCode(String symbolTableCode) {
        this.symbolTableCode = symbolTableCode;
    }
}

