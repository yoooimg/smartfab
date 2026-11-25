package dps.rest;

import dps.utility.LineRegistry;
import dps.utility.Line;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link LineRegisterController}, using
 * Spring's MVC test slice: the real Spring MVC dispatch/serialization
 * machinery runs, but {@link LineRegistry} is replaced with a Mockito
 * mock via {@code @MockitoBean}.
 */
@WebMvcTest(LineRegisterController.class)
public class LineRegisterControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LineRegistry lineRegistry;

    @Test
    void register_newLine_returnsOkWithOtherLines() throws Exception {
        Line self = new Line(1, "localhost", 9001);
        Line other = new Line(2, "localhost", 9002);
        when(lineRegistry.register(self)).thenReturn(true);
        when(lineRegistry.otherLines(self)).thenReturn(List.of(other));
        mockMvc.perform(post("/line")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":1,\"address\":\"localhost\",\"port\":9001}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(2))
                .andExpect(jsonPath("$[0].address").value("localhost"))
                .andExpect(jsonPath("$[0].port").value(9002));
    }

    @Test
    void register_duplicateId_returnsConflict() throws Exception {
        when(lineRegistry.register(any(Line.class))).thenReturn(false);
        mockMvc.perform(post("/line")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":1,\"address\":\"localhost\",\"port\":9001}"))
                .andExpect(status().isConflict());
        verify(lineRegistry, never()).otherLines(any());
    }

    @Test
    void deregister_existingId_returnsOkWithRemainingLines() throws Exception {
        when(lineRegistry.remove(1)).thenReturn(true);
        when(lineRegistry.all()).thenReturn(List.of(new Line(2, "localhost", 9002)));
        mockMvc.perform(delete("/line/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(2));
    }

    @Test
    void deregister_unknownId_returnsNotFound() throws Exception {
        when(lineRegistry.remove(1)).thenReturn(false);
        mockMvc.perform(delete("/line/1"))
                .andExpect(status().isNotFound());
    }
}
