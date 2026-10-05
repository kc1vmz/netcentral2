package com.kc1vmz.netcentral.communication;

import static org.assertj.core.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class SerialPortsTest {
    @Test void discoverySortsAndKeepsDisconnectedAssignments() {
        var assigned = List.of(new SerialPorts.Assignment("COM2","Radio",true),
                new SerialPorts.Assignment("COM9","Unplugged",false));
        var result = SerialPorts.discover(assigned, () -> List.of(
                new SerialPorts.Device("COM10","USB"), new SerialPorts.Device("COM2","TNC")));
        assertThat(result.error()).isNull();
        assertThat(result.ports()).extracting(SerialPorts.Port::device).containsExactly("COM2","COM9","COM10");
        assertThat(result.ports().get(0).assignments()).containsExactly(assigned.get(0));
        assertThat(result.ports().get(0).detected()).isTrue();
        assertThat(result.ports().get(1).detected()).isFalse();
    }
    @Test void unavailableLibraryPreservesManualConfiguration() {
        var result = SerialPorts.discover(List.of(new SerialPorts.Assignment("COM9","Radio",false)),
                () -> { throw new UnsatisfiedLinkError("native library unavailable"); });
        assertThat(result.error()).contains("manually");
        assertThat(result.ports()).hasSize(1);
        assertThat(result.ports().getFirst().detected()).isFalse();
        assertThat(SerialPorts.discover(List.of(), List::of).ports()).isEmpty();
    }
    @Test void serialIdentityNormalizesWindowsAliasesAndLinuxPaths() {
        assertThat(SerialOwnership.identity("\\\\.\\com02",true)).isEqualTo("COM2");
        assertThat(SerialOwnership.identity("/dev/serial/../ttyUSB0",false))
                .isEqualTo(SerialOwnership.identity("/dev/ttyUSB0",false));
    }
}
