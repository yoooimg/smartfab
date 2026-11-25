package dps.rest;

import dps.utility.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

/**
 * Integration tests for {@link AdminClientController}, using Spring's
 * MVC test slice with {@link LineRegistry}, {@link LineStateStore} and
 * {@link TelemetryStore} all replaced with Mockito mocks via {@code @MockitoBean}.
 */
@WebMvcTest(AdminClientController.class)
public class AdminClientControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LineRegistry lineRegistry;

    @MockitoBean
    private LineStateStore lineStateStore;

    @MockitoBean
    private TelemetryStore telemetryStore;

    @Test
    void all_combineRegistryStateAndTelemetry_intoOneSnapshotPerLine() throws Exception {
        Line line = new Line(1, "localhost", 9001);
        when(lineRegistry.all()).thenReturn(List.of(line));
        when(lineStateStore.get(1)).thenReturn(OperationalState.WAITING_FOR_CALIBRATION);
        when(telemetryStore.get(1)).thenReturn(List.of(new Telemetry(70.0, 1000L)));
        mockMvc.perform(get("/line/all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].line.id").value(1))
                .andExpect(jsonPath("$[0].line.address").value("localhost"))
                .andExpect(jsonPath("$[0].state").value("WAITING_FOR_CALIBRATION"))
                .andExpect(jsonPath("$[0].telemetries[0].vibration").value(70.0))
                .andExpect(jsonPath("$[0].telemetries[0].timestamp").value(1000));
    }

    @Test
    void all_withNoLineRegistered_returnsEmptyList() throws Exception {
        when(lineRegistry.all()).thenReturn(List.of());
        mockMvc.perform(get("/line/all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void state_knownLine_returnsItsCurrentState() throws Exception {
        when(lineRegistry.all()).thenReturn(List.of(new Line(1, "localhost", 9001)));
        when(lineStateStore.get(1)).thenReturn(OperationalState.UNDER_CALIBRATION);
        mockMvc.perform(get("/line/state/1"))
                .andExpect(status().isOk())
                .andExpect(content().string("\"UNDER_CALIBRATION\""));
    }

    @Test
    void state_unknownLine_returnsNotFound() throws Exception {
        when(lineStateStore.get(1)).thenReturn(null);
        mockMvc.perform(get("/line/state/1"))
                .andExpect(status().isNotFound());
    }

    @Test
    void state_idNotAmongOtherRegisteredLines_returnsNotFound() throws Exception {
        when(lineRegistry.all()).thenReturn(List.of(
                new Line(2, "localhost", 9002),
                new Line(3, "localhost", 9003)
        ));
        mockMvc.perform(get("/line/state/1"))
                .andExpect(status().isNotFound());
    }

    @Test
    void state_registeredButNoStateYet_returnsNotFound() throws Exception {
        when(lineRegistry.all()).thenReturn(List.of(new Line(1, "localhost", 9001)));
        when(lineStateStore.get(1)).thenReturn(null);
        mockMvc.perform(get("/line/state/1"))
                .andExpect(status().isNotFound());
    }

    @Test
    void average_delegatesToTelemetryStore_withTheGivenRange() throws Exception {
        when(telemetryStore.average(1, 1000L, 2000L)).thenReturn(70.0);
        mockMvc.perform(get("/line/1/average/1000/2000"))
                .andExpect(status().isOk())
                .andExpect(content().string("70.0"));
    }
}
