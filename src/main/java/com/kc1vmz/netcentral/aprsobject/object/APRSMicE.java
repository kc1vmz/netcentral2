package com.kc1vmz.netcentral.aprsobject.object;

import com.kc1vmz.netcentral.aprsobject.enums.MicECommentType;
import com.kc1vmz.netcentral.aprsobject.interfaces.APRSPacketInterface;

public class APRSMicE implements APRSPacketInterface {
    private byte [] data;
    private byte [] header;
    private String callsignFrom;
    private String callsignTo;
    private String applicationName;
    private byte dti;
    private String lat;
    private String lon;
    private MicECommentType commentType;
    private String status;
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
    public byte getDti() {
        return dti;
    }
    public void setDti(byte dti) {
        this.dti = dti;
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
    public MicECommentType getCommentType() {
        return commentType;
    }
    public void setCommentType(MicECommentType commentType) {
        this.commentType = commentType;
    }
    public String getStatus() {
        return status;
    }
    public void setStatus(String status) {
        this.status = status;
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

