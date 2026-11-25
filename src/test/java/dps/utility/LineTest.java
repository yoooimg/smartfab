package dps.utility;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link Line}.
 */
class LineTest {
    @Test
    void toString_containsIdAddressAndPort() {
        Line line = new Line(1, "localhost", 9001);
        assertThat(line.toString()).isEqualTo("{id=1, address=localhost, port=9001}");
    }
}