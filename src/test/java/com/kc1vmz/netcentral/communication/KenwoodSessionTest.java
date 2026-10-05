package com.kc1vmz.netcentral.communication;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class KenwoodSessionTest {
    private CommunicationConfig config(boolean transmit) {
        return new CommunicationConfig("kenwood",0,"","KENWOOD_SERIAL",true,transmit,null,null,"N1ABC-10",null,null,
            "COM9",9600,null,null,"WIDE1-1,WIDE2-1",null);
    }
    static class Radio extends OutputStream implements AutoCloseable {
        final PipedInputStream input=new PipedInputStream(8192);
        final PipedOutputStream received;
        final List<String> commands=new ArrayList<>(),payloads=new ArrayList<>();
        final StringBuilder text=new StringBuilder();
        boolean converse;
        String reject;
        Radio() throws IOException {received=new PipedOutputStream(input);}
        void receive(String value) throws IOException {received.write(value.getBytes(StandardCharsets.US_ASCII));received.flush();}
        @Override public void write(int value) throws IOException {
            if(value==3){converse=false;receive("cmd:");return;}
            if(value=='\r') {
                String line=text.toString();text.setLength(0);
                if(converse){payloads.add(line);receive("N2ABC>APANC1::NET001   :CI{1\r");}
                else {commands.add(line);if(line.equals(reject)){receive("?EH\r cmd:");return;}if(line.equals("CONVERS"))converse=true;else receive("cmd:");}
            }else text.append((char)value);
        }
        @Override public void close() throws IOException {received.close();input.close();}
    }
    @Test void initializesSendsPayloadAndReceivesDuringTransmission() throws Exception {
        try(var radio=new Radio();var session=new KenwoodSession(radio.input,radio,config(true))) {
            session.initialize();
            assertThat(radio.commands).startsWith("RESET","MYCALL N1ABC-10","UNPROTO APANC1 VIA WIDE1-1,WIDE2-1");
            assertThat(radio.commands).contains("MONITOR ON","ECHO OFF","PACLEN 255");
            session.send(new Packet("NET001","APANC1",List.of(),":N2ABC    :Hello{7"));
            assertThat(radio.payloads).containsExactly(":N2ABC    :Hello{7");
            Packet packet=session.read();
            assertThat(packet.source()).isEqualTo("N2ABC");
            assertThat(packet.information()).isEqualTo(":NET001   :CI{1");
            radio.receive("N3ABC>APANC1:\r");radio.receive("!4258.00N/07127.00W>test\r");
            assertThat(session.read().source()).isEqualTo("N3ABC");
            assertThatThrownBy(()->session.send(new Packet("NET001","APANC1",List.of(),"bad\003payload"))).isInstanceOf(IOException.class);
        }
    }
    @Test void rejectedInitializationFailsAndFragmentsArePreserved() throws Exception {
        try(var radio=new Radio();var session=new KenwoodSession(radio.input,radio,config(true))) {
            radio.reject="MYCALL N1ABC-10";
            assertThatThrownBy(session::initialize).isInstanceOf(IOException.class).hasMessageContaining("rejected");
            assertThat(radio.payloads).isEmpty();
        }
        try(var radio=new Radio();var session=new KenwoodSession(radio.input,radio,config(true))) {
            session.initialize();
            radio.receive("N4ABC>AP");
            assertThat(session.read()).isNull();
            radio.receive("ANC1:>Ready\rN5ABC>APANC1:>Also ready\r");
            assertThat(session.read().source()).isEqualTo("N4ABC");
            assertThat(session.read().source()).isEqualTo("N5ABC");
        }
    }
    @Test void receiveOnlyDoesNotTransmit() throws Exception {
        try(var radio=new Radio();var session=new KenwoodSession(radio.input,radio,config(false))) {
            session.initialize();
            assertThatThrownBy(()->session.send(new Packet("NET001","APANC1",List.of(),"hello"))).isInstanceOf(IOException.class);
            assertThat(radio.payloads).isEmpty();
        }
    }
}
